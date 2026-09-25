package com.asif.vmreader

/** Kotlin side of the C++ bridge (jni.cpp). */
object WhisperLib {
    init { System.loadLibrary("vmreader") }

    /** Load model file → pointer (0 = failed). */
    external fun initModel(path: String): Long

    /** Free the model. */
    external fun freeModel(ctx: Long)

    /** Transcribe one ≤14 s part (16 kHz mono) → UTF-8 bytes. */
    external fun transcribe(ctx: Long, samples: FloatArray, threads: Int): ByteArray
}
