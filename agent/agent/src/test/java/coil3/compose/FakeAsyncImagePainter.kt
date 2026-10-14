package coil3.compose

/**
 * Test double for Coil 3's AsyncImagePainter, with the members ComposeVisuals reads by
 * reflection: the MutableState behind its `painter` property (the `painter$delegate` field
 * Kotlin generates for `var painter by mutableStateOf(…)`), and its `state` StateFlow, whose
 * value's painter is what `painter` is set from. Either can be left out (null) to stand for a
 * release in which it can't be read.
 */
class AsyncImagePainter(
    @JvmField val `painter$delegate`: Any?,
    private val state: Any? = null,
) {
    fun getState(): Any? = state
}

/** A MutableState: its value read through `getValue()`, as State.value is. */
class FakeMutableState(val value: Any?)

/** A StateFlow: its current value read through `getValue()`. */
class FakeStateFlow(val value: Any?)

/** An AsyncImagePainter.State (Success, Loading, Error or Empty): the painter it shows, or none. */
class FakeImageState(val painter: Any?)
