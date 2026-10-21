package androidx.activity.result.contract
abstract class ActivityResultContract<I, O>
class ActivityResultContracts {
    open class CreateDocument(val mimeType: String) : ActivityResultContract<String, android.net.Uri?>()
    class RequestPermission : ActivityResultContract<String, Boolean>()
}
