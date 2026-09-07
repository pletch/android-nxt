package org.owntracks.android.ui.status.logs

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ShareCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlin.random.Random
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.owntracks.android.BaseApp
import org.owntracks.android.BuildConfig
import org.owntracks.android.R
import org.owntracks.android.databinding.UiPreferencesLogsBinding
import org.owntracks.android.logging.CrashLog
import org.owntracks.android.logging.LogEntry
import timber.log.Timber

@AndroidEntryPoint
class LogViewerActivity : AppCompatActivity() {
  val viewModel: LogViewerViewModel by viewModels()

  private val shareIntentActivityLauncher =
      registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}

  private lateinit var logAdapter: LogEntryAdapter
  private var logExportUri: Uri? = null
  private var recyclerView: RecyclerView? = null
  private var clearButton: MenuItem? = null
  private lateinit var binding: UiPreferencesLogsBinding
  private var collectorJob: Job? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    super.onCreate(savedInstanceState)
    binding =
        DataBindingUtil.setContentView<UiPreferencesLogsBinding>(
                this,
                R.layout.ui_preferences_logs,
            )
            .apply {
              lifecycleOwner = this@LogViewerActivity
              setSupportActionBar(appbar.toolbar)

              // Handle window insets for edge-to-edge
              val fabBaseMarginBottom =
                  (shareFab.layoutParams as CoordinatorLayout.LayoutParams).bottomMargin
              ViewCompat.setOnApplyWindowInsetsListener(frame) { _, windowInsets ->
                val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                appbar.root.updatePadding(top = insets.top)
                shareFab.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                  bottomMargin = fabBaseMarginBottom + insets.bottom
                }
                WindowInsetsCompat.CONSUMED
              }
            }

    supportActionBar?.apply {
      setDisplayShowHomeEnabled(true)
      setDisplayHomeAsUpEnabled(true)
    }

    @Suppress("DEPRECATION")
    logAdapter =
        LogEntryAdapter(
            LogPalette(
                resources.getColor(com.mikepenz.materialize.R.color.primary),
                resources.getColor(R.color.log_debug_tag_color),
                resources.getColor(R.color.log_info_tag_color),
                resources.getColor(R.color.log_warning_tag_color),
                resources.getColor(R.color.log_error_tag_color),
            )
        )
    restartLogCollector()

    binding.logsRecyclerView.apply {
      recyclerView = this
      layoutManager = LinearLayoutManager(context)
      adapter = logAdapter
    }
    binding.shareFab.setOnClickListener {
      val key = "${getRandomHexString()}/debug=${viewModel.isDebugEnabled()}/owntracks-debug.txt"
      logExportUri = "content://${BuildConfig.APPLICATION_ID}.log/$key".toUri()
      val shareIntent =
          ShareCompat.IntentBuilder(this)
              .setType("text/plain")
              .setSubject(getString(R.string.exportLogFileSubject))
              .setChooserTitle(R.string.exportLogFilePrompt)
              .setStream(logExportUri)
              .createChooserIntent()
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // Temporary. No need to revoke.
              .also { Timber.v("Created share intent of r$logExportUri") }
      grantUriPermission("android", logExportUri, Intent.FLAG_GRANT_READ_URI_PERMISSION).also {
        Timber.v("Granted READ_URI_PERMISSION permission to $logExportUri")
      }
      shareIntentActivityLauncher.launch(shareIntent)
    }
  }

  private fun restartLogCollector() {
    collectorJob?.cancel("Restarting")
    logAdapter.clearLogs()
    collectorJob = lifecycleScope.launch {
      viewModel.logLines().flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect {
        if (viewModel.isDebugEnabled() || it.priority >= Log.INFO) {
          updateAdapterWithLogLines(it)
        }
      }
    }
  }

  private fun updateAdapterWithLogLines(logEntry: LogEntry) {
    val atTheBottom = !binding.logsRecyclerView.canScrollVertically(1)
    logAdapter.addLogLine(logEntry)
    if (atTheBottom) {
      binding.logsRecyclerView.scrollToPosition(logAdapter.itemCount - 1)
    }
  }

  override fun onResume() {
    super.onResume()
    // The crash notification has done its job once the user is looking at the log. The reports
    // themselves stay until they're explicitly cleared, so they're still in anything shared here.
    NotificationManagerCompat.from(this)
        .cancel(BaseApp.NOTIFICATION_TAG_CRASH, BaseApp.NOTIFICATION_ID_CRASH)
    this.recyclerView?.scrollToPosition(logAdapter.itemCount - 1)
  }

  override fun onCreateOptionsMenu(menu: Menu): Boolean {
    menuInflater.inflate(R.menu.log_viewer, menu)
    clearButton = menu.findItem(R.id.clear_log)
    return true
  }

  override fun onPrepareOptionsMenu(menu: Menu): Boolean {
    menu.findItem(R.id.show_debug_logs).isChecked = viewModel.isDebugEnabled()
    return true
  }

  override fun onOptionsItemSelected(item: MenuItem): Boolean {
    return when (item.itemId) {
      android.R.id.home -> {
        finish()
        true
      }
      R.id.clear_log -> {
        // Clearing the log is the user's acknowledgement of any crash reports: until then they're
        // kept on disk so that they survive the log buffer rolling over, and are attached to every
        // export.
        CrashLog.forContext(this).acknowledge()
        viewModel.clearLog()
        restartLogCollector()
        true
      }
      R.id.show_debug_logs -> {
        item.isChecked = !item.isChecked
        viewModel.enableDebugLogs(item.isChecked)
        restartLogCollector()
        true
      }
      else -> super.onOptionsItemSelected(item)
    }
  }

  private fun getRandomHexString(): String {
    return Random.nextInt(0X1000000).toString(16)
  }
}
