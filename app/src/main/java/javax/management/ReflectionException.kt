package javax.management

class ReflectionException(
    private val target: Exception?,
    message: String? = target?.message,
) : Exception(message, target) {
    fun getTargetException(): Exception? = target
}
