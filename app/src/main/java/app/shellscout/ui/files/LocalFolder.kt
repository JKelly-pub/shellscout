package app.shellscout.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * FT-02: local storage is reached only through the Storage Access Framework. The user picks a root
 * folder once (internal storage, SD card, USB drive…); the grant is persisted and remembered.
 */
object LocalFolder {
    private const val PREFS = "local_folder"
    private const val KEY = "tree_uri"

    fun saved(context: Context): DocumentFile? {
        val uri = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return null
        val granted = context.contentResolver.persistedUriPermissions.any { it.uri.toString() == uri && it.isWritePermission }
        if (!granted) return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))?.takeIf { it.canRead() }
    }

    fun save(context: Context, treeUri: Uri): DocumentFile? {
        context.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, treeUri.toString()).apply()
        return DocumentFile.fromTreeUri(context, treeUri)
    }
}
