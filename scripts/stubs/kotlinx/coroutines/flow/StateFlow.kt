package kotlinx.coroutines.flow

interface StateFlow<out T> {
    val value: T
}

class MutableStateFlow<T>(initialValue: T) : StateFlow<T> {
    override var value: T = initialValue
}

fun <T> MutableStateFlow<T>.asStateFlow(): StateFlow<T> = this
