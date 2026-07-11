package org.owntracks.android.ui.contacts

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.launch
import org.owntracks.android.R
import org.owntracks.android.data.repos.ContactsRepoChange
import org.owntracks.android.databinding.UiContactsBinding
import org.owntracks.android.model.Contact
import org.owntracks.android.test.ThresholdIdlingResourceInterface
import org.owntracks.android.ui.DrawerProvider
import org.owntracks.android.ui.map.MapActivity
import org.owntracks.android.ui.mixins.AppBarInsetHandler
import org.owntracks.android.ui.mixins.ServiceStarter
import timber.log.Timber

@AndroidEntryPoint
class ContactsActivity :
    AppCompatActivity(),
    AdapterClickListener<Contact>,
    ServiceStarter by ServiceStarter.Impl(),
    AppBarInsetHandler by AppBarInsetHandler.Impl() {
  @Inject lateinit var drawerProvider: DrawerProvider

  @Inject
  @Named("contactsActivityIdlingResource")
  lateinit var contactsCountingIdlingResource: ThresholdIdlingResourceInterface

  private val viewModel: ContactsViewModel by viewModels()
  private lateinit var contactsAdapter: ContactsAdapter

  override fun onCreate(savedInstanceState: Bundle?) {
    enableEdgeToEdge()
    startService(this)
    super.onCreate(savedInstanceState)
    contactsAdapter = ContactsAdapter(this, viewModel.coroutineScope)
    val binding =
        DataBindingUtil.setContentView<UiContactsBinding>(this, R.layout.ui_contacts).apply {
          vm = viewModel
          appbar.toolbar.run {
            setSupportActionBar(this)
            drawerProvider.attach(this, drawerLayout, navigationView)
          }
          contactsRecyclerView.run {
            layoutManager = LinearLayoutManager(this@ContactsActivity)
            adapter = contactsAdapter
          }

          applyAppBarEdgeToEdgeInsets(drawerLayout, appbar.root, navigationView)
        }

    contactsAdapter.setContactList(viewModel.contacts.values)

    // Trigger a geocode refresh on startup, because future refreshes will only be triggered on
    // update events
    viewModel.contacts.values.forEach(viewModel::refreshGeocode)

    // Observe changes to the contacts repo while STARTED and forward them onto the
    // [ContactsAdapter], optionally updating the geocode for the contact. Below STARTED there is
    // no subscriber and the repo flow has no replay, so events emitted while backgrounded are
    // dropped — reconcile against the authoritative repo state on every re-entry, then apply
    // incremental events. (A bare collect instead would keep this backgrounded activity
    // reverse-geocoding every contact update for as long as it exists.)
    lifecycleScope.launch {
      repeatOnLifecycle(Lifecycle.State.STARTED) {
        contactsAdapter.setContactList(viewModel.contacts.values)
        updateListVisibility(binding)
        viewModel.contactUpdatedEvent.collect {
          Timber.v("Received contactUpdatedEvent $it")
          when (it) {
            is ContactsRepoChange.ContactAdded -> {
              contactsAdapter.addContact(it.contact)
              viewModel.refreshGeocode(it.contact)
            }
            is ContactsRepoChange.ContactRemoved -> contactsAdapter.removeContact(it.contact)
            is ContactsRepoChange.ContactLocationUpdated -> {
              contactsAdapter.updateContact(it.contact)
              viewModel.refreshGeocode(it.contact)
            }
            is ContactsRepoChange.ContactCardUpdated -> contactsAdapter.updateContact(it.contact)
            is ContactsRepoChange.AllCleared -> contactsAdapter.clearAll()
          }
          updateListVisibility(binding)

          contactsCountingIdlingResource.run { if (!isIdleNow) decrement() }
        }
      }
    }
  }

  private fun updateListVisibility(binding: UiContactsBinding) {
    binding.run {
      placeholder.visibility = if (viewModel.contacts.isEmpty()) View.VISIBLE else View.GONE
      contactsRecyclerView.visibility =
          if (viewModel.contacts.isEmpty()) View.GONE else View.VISIBLE
    }
  }

  override fun onClick(item: Contact, view: View, longClick: Boolean) {
    startActivity(
        Intent(this, MapActivity::class.java)
            .putExtra(
                "_args", Bundle().apply { putString(MapActivity.BUNDLE_KEY_CONTACT_ID, item.id) }))
  }

  override fun onResume() {
    super.onResume()
    drawerProvider.updateHighlight()
  }
}
