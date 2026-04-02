package com.wapp.wearmessage.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

internal data class DeviceContact(
    val id: Long,
    val displayName: String,
    val phoneNumber: String?,
)

internal sealed interface ContactsPickerUiState {
    data object Loading : ContactsPickerUiState
    data object PermissionRequired : ContactsPickerUiState
    data object Empty : ContactsPickerUiState
    data class Error(val message: String) : ContactsPickerUiState
    data class Success(val contacts: List<DeviceContact>) : ContactsPickerUiState
}

internal fun checkContactsPermission(
    context: Context,
): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED

internal fun queryDeviceContacts(
    context: Context,
): List<DeviceContact> {
    val contactsById = LinkedHashMap<Long, DeviceContact>()

    val contactsProjection =
        arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )
    val contactsSortOrder =
        "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC, " +
            "${ContactsContract.Contacts._ID} ASC"
    context.contentResolver
        .query(
            ContactsContract.Contacts.CONTENT_URI,
            contactsProjection,
            null,
            null,
            contactsSortOrder,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.Contacts._ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            if (idIndex == -1 || nameIndex == -1) {
                return emptyList()
            }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val displayName = cursor.getString(nameIndex)?.trim().orEmpty()
                if (displayName.isBlank()) {
                    continue
                }
                contactsById[id] = DeviceContact(id = id, displayName = displayName, phoneNumber = null)
            }
        }

    val phoneProjection =
        arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
        )
    val phoneSortOrder =
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC, " +
            "${ContactsContract.CommonDataKinds.Phone.IS_PRIMARY} DESC, " +
            "${ContactsContract.CommonDataKinds.Phone._ID} ASC"
    context.contentResolver
        .query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            phoneProjection,
            null,
            null,
            phoneSortOrder,
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY)
            val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val primaryIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.IS_PRIMARY)
            if (idIndex == -1 || nameIndex == -1 || numberIndex == -1 || primaryIndex == -1) {
                return emptyList()
            }
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val displayName = cursor.getString(nameIndex)?.trim().orEmpty()
                val number = cursor.getString(numberIndex)?.trim()?.ifBlank { null }
                if (displayName.isBlank()) {
                    continue
                }
                val candidate = DeviceContact(id = id, displayName = displayName, phoneNumber = number)
                val existing = contactsById[id]
                val isPrimary = cursor.getInt(primaryIndex) == 1
                if (existing == null || existing.phoneNumber == null || (isPrimary && candidate.phoneNumber != null)) {
                    contactsById[id] = candidate
                }
            }
        }
    return contactsById.values
        .sortedBy { contact ->
            contact.displayName.lowercase()
        }
}
