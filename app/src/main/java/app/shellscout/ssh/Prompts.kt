package app.shellscout.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import java.util.UUID

enum class ConflictChoice { OVERWRITE, SKIP, RENAME, RESUME, CANCEL }
data class ConflictAnswer(val choice: ConflictChoice, val applyToAll: Boolean)
data class SecretAnswer(val secret: CharArray, val remember: Boolean)

/** Everything the background SSH layer needs to ask the user. Each prompt is answered exactly once. */
sealed class Prompt<T> {
    val id: String = UUID.randomUUID().toString()
    internal val answer = CompletableDeferred<T>()

    /** SC-03: first connection to this host — show the fingerprint and ask to trust it. */
    class HostKeyNew(val host: String, val port: Int, val keyType: String, val fingerprint: String) : Prompt<Boolean>()

    /** SC-03: the key differs from the stored one. Default action is to refuse. */
    class HostKeyChanged(
        val host: String, val port: Int, val keyType: String,
        val oldFingerprint: String, val newFingerprint: String,
    ) : Prompt<Boolean>()

    class Password(val host: String, val username: String, val offerSave: Boolean) : Prompt<SecretAnswer?>()
    class Passphrase(val keyName: String) : Prompt<SecretAnswer?>()

    /** AU-05: a server-defined prompt, e.g. an OTP code. */
    class Interactive(val title: String, val instruction: String, val prompt: String, val echo: Boolean) : Prompt<String?>()

    /** FT-08 */
    class FileConflict(val name: String, val existingSize: Long, val incomingSize: Long, val canResume: Boolean) :
        Prompt<ConflictAnswer>()

    /** SN-04: fill in {{variables}} before sending a snippet. */
    class SnippetVariables(val snippetName: String, val variables: List<String>) : Prompt<Map<String, String>?>()
}

class PromptBroker {
    private val _pending = MutableStateFlow<List<Prompt<*>>>(emptyList())
    val pending: StateFlow<List<Prompt<*>>> = _pending.asStateFlow()

    suspend fun <T> ask(prompt: Prompt<T>): T {
        _pending.update { it + prompt }
        try {
            return prompt.answer.await()
        } finally {
            _pending.update { list -> list.filterNot { it.id == prompt.id } }
        }
    }

    /** For sshj callbacks, which run on sshj's own threads and must return synchronously. */
    fun <T> askBlocking(prompt: Prompt<T>): T = runBlocking { ask(prompt) }

    fun <T> answer(prompt: Prompt<T>, value: T) {
        prompt.answer.complete(value)
    }
}
