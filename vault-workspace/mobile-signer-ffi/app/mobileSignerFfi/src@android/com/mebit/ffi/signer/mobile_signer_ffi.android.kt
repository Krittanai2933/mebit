

@file:Suppress("RemoveRedundantBackticks")

package com.mebit.ffi.signer

// Common helper code.
//
// Ideally this would live in a separate .kt file where it can be unittested etc
// in isolation, and perhaps even published as a re-useable package.
//
// However, it's important that the details of how this helper code works (e.g. the
// way that different builtin types are passed across the FFI) exactly match what's
// expected by the Rust code on the other side of the interface. In practice right
// now that means coming from the exact some version of `uniffi` that was used to
// compile the Rust component. The easiest way to ensure this is to bundle the Kotlin
// helpers directly inline like we're doing here.

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext


internal typealias Pointer = com.sun.jna.Pointer
internal val NullPointer: Pointer? = com.sun.jna.Pointer.NULL
internal fun Pointer.toLong(): Long = Pointer.nativeValue(this)
internal fun kotlin.Long.toPointer() = com.sun.jna.Pointer(this)


@kotlin.jvm.JvmInline
public value class ByteBuffer(private val inner: java.nio.ByteBuffer) {
    init {
        inner.order(java.nio.ByteOrder.BIG_ENDIAN)
    }

    public fun internal(): java.nio.ByteBuffer = inner

    public fun limit(): Int = inner.limit()

    public fun position(): Int = inner.position()

    public fun hasRemaining(): Boolean = inner.hasRemaining()

    public fun get(): Byte = inner.get()

    public fun get(bytesToRead: Int): ByteArray = ByteArray(bytesToRead).apply(inner::get)

    public fun getShort(): Short = inner.getShort()

    public fun getInt(): Int = inner.getInt()

    public fun getLong(): Long = inner.getLong()

    public fun getFloat(): Float = inner.getFloat()

    public fun getDouble(): Double = inner.getDouble()

    public fun put(value: Byte) {
        inner.put(value)
    }

    public fun put(src: ByteArray) {
        inner.put(src)
    }

    public fun putShort(value: Short) {
        inner.putShort(value)
    }

    public fun putInt(value: Int) {
        inner.putInt(value)
    }

    public fun putLong(value: Long) {
        inner.putLong(value)
    }

    public fun putFloat(value: Float) {
        inner.putFloat(value)
    }

    public fun putDouble(value: Double) {
        inner.putDouble(value)
    }
}
public fun RustBuffer.setValue(array: RustBufferByValue) {
    this.data = array.data
    this.len = array.len
    this.capacity = array.capacity
}

internal object RustBufferHelper {
    internal fun allocValue(size: ULong = 0UL): RustBufferByValue = uniffiRustCall { status ->
        // Note: need to convert the size to a `Long` value to make this work with JVM.
        UniffiLib.ffi_mobile_signer_ffi_rustbuffer_alloc(size.toLong(), status)
    }.also {
        if(it.data == null) {
            throw RuntimeException("RustBuffer.alloc() returned null data pointer (size=${size})")
        }
    }

    internal fun free(buf: RustBufferByValue) = uniffiRustCall { status ->
        UniffiLib.ffi_mobile_signer_ffi_rustbuffer_free(buf, status)
    }
}

@Structure.FieldOrder("capacity", "len", "data")
public open class RustBufferStruct(
    // Note: `capacity` and `len` are actually `ULong` values, but JVM only supports signed values.
    // When dealing with these fields, make sure to call `toULong()`.
    @JvmField public var capacity: Long,
    @JvmField public var len: Long,
    @JvmField public var data: Pointer?,
) : Structure() {
    public constructor(): this(0.toLong(), 0.toLong(), null)

    public class ByValue(
        capacity: Long,
        len: Long,
        data: Pointer?,
    ): RustBuffer(capacity, len, data), Structure.ByValue {
        public constructor(): this(0.toLong(), 0.toLong(), null)
    }

    /**
     * The equivalent of the `*mut RustBuffer` type.
     * Required for callbacks taking in an out pointer.
     *
     * Size is the sum of all values in the struct.
     */
    public class ByReference(
        capacity: Long,
        len: Long,
        data: Pointer?,
    ): RustBuffer(capacity, len, data), Structure.ByReference {
        public constructor(): this(0.toLong(), 0.toLong(), null)
    }
}

public typealias RustBuffer = RustBufferStruct
public typealias RustBufferByValue = RustBufferStruct.ByValue

internal fun RustBuffer.asByteBuffer(): ByteBuffer? {
    require(this.len <= Int.MAX_VALUE) {
        val length = this.len
        "cannot handle RustBuffer longer than Int.MAX_VALUE bytes: length is $length"
    }
    return ByteBuffer(data?.getByteBuffer(0L, this.len) ?: return null)
}

internal fun RustBufferByValue.asByteBuffer(): ByteBuffer? {
    require(this.len <= Int.MAX_VALUE) {
        val length = this.len
        "cannot handle RustBuffer longer than Int.MAX_VALUE bytes: length is $length"
    }
    return ByteBuffer(data?.getByteBuffer(0L, this.len) ?: return null)
}

// This is a helper for safely passing byte references into the rust code.
// It's not actually used at the moment, because there aren't many things that you
// can take a direct pointer to in the JVM, and if we're going to copy something
// then we might as well copy it into a `RustBuffer`. But it's here for API
// completeness.

@Structure.FieldOrder("len", "data")
internal open class ForeignBytesStruct : Structure() {
    @JvmField var len: Int = 0
    @JvmField var data: Pointer? = null

    internal class ByValue : ForeignBytes(), Structure.ByValue
}
internal typealias ForeignBytes = ForeignBytesStruct
internal typealias ForeignBytesByValue = ForeignBytesStruct.ByValue

public interface FfiConverter<KotlinType, FfiType> {
    // Convert an FFI type to a Kotlin type
    public fun lift(value: FfiType): KotlinType

    // Convert an Kotlin type to an FFI type
    public fun lower(value: KotlinType): FfiType

    // Read a Kotlin type from a `ByteBuffer`
    public fun read(buf: ByteBuffer): KotlinType

    // Calculate bytes to allocate when creating a `RustBuffer`
    //
    // This must return at least as many bytes as the write() function will
    // write. It can return more bytes than needed, for example when writing
    // Strings we can't know the exact bytes needed until we the UTF-8
    // encoding, so we pessimistically allocate the largest size possible (3
    // bytes per codepoint).  Allocating extra bytes is not really a big deal
    // because the `RustBuffer` is short-lived.
    public fun allocationSize(value: KotlinType): ULong

    // Write a Kotlin type to a `ByteBuffer`
    public fun write(value: KotlinType, buf: ByteBuffer)

    // Lower a value into a `RustBuffer`
    //
    // This method lowers a value into a `RustBuffer` rather than the normal
    // FfiType.  It's used by the callback interface code.  Callback interface
    // returns are always serialized into a `RustBuffer` regardless of their
    // normal FFI type.
    public fun lowerIntoRustBuffer(value: KotlinType): RustBufferByValue {
        val rbuf = RustBufferHelper.allocValue(allocationSize(value))
        val bbuf = rbuf.asByteBuffer()!!
        write(value, bbuf)
        return RustBufferByValue(
            capacity = rbuf.capacity,
            len = bbuf.position().toLong(),
            data = rbuf.data,
        )
    }

    // Lift a value from a `RustBuffer`.
    //
    // This here mostly because of the symmetry with `lowerIntoRustBuffer()`.
    // It's currently only used by the `FfiConverterRustBuffer` class below.
    public fun liftFromRustBuffer(rbuf: RustBufferByValue): KotlinType {
        val byteBuf = rbuf.asByteBuffer()!!
        try {
           val item = read(byteBuf)
           if (byteBuf.hasRemaining()) {
               throw RuntimeException("junk remaining in buffer after lifting, something is very wrong!!")
           }
           return item
        } finally {
            RustBufferHelper.free(rbuf)
        }
    }
}

// FfiConverter that uses `RustBuffer` as the FfiType
public interface FfiConverterRustBuffer<KotlinType>: FfiConverter<KotlinType, RustBufferByValue> {
    override fun lift(value: RustBufferByValue): KotlinType = liftFromRustBuffer(value)
    override fun lower(value: KotlinType): RustBufferByValue = lowerIntoRustBuffer(value)
}

internal const val UNIFFI_CALL_SUCCESS = 0.toByte()
internal const val UNIFFI_CALL_ERROR = 1.toByte()
internal const val UNIFFI_CALL_UNEXPECTED_ERROR = 2.toByte()

// Default Implementations
internal fun UniffiRustCallStatus.isSuccess(): Boolean
    = code == UNIFFI_CALL_SUCCESS

internal fun UniffiRustCallStatus.isError(): Boolean
    = code == UNIFFI_CALL_ERROR

internal fun UniffiRustCallStatus.isPanic(): Boolean
    = code == UNIFFI_CALL_UNEXPECTED_ERROR

internal fun UniffiRustCallStatusByValue.isSuccess(): Boolean
    = code == UNIFFI_CALL_SUCCESS

internal fun UniffiRustCallStatusByValue.isError(): Boolean
    = code == UNIFFI_CALL_ERROR

internal fun UniffiRustCallStatusByValue.isPanic(): Boolean
    = code == UNIFFI_CALL_UNEXPECTED_ERROR

// Each top-level error class has a companion object that can lift the error from the call status's rust buffer
public interface UniffiRustCallStatusErrorHandler<E> {
    public fun lift(errorBuf: RustBufferByValue): E
}

// Helpers for calling Rust
// In practice we usually need to be synchronized to call this safely, so it doesn't
// synchronize itself

// Call a rust function that returns a Result<>.  Pass in the Error class companion that corresponds to the Err
internal inline fun <U, E: kotlin.Exception> uniffiRustCallWithError(errorHandler: UniffiRustCallStatusErrorHandler<E>, crossinline callback: (UniffiRustCallStatus) -> U): U {
    return UniffiRustCallStatusHelper.withReference() { status ->
        val returnValue = callback(status)
        uniffiCheckCallStatus(errorHandler, status)
        returnValue
    }
}

// Check `status` and throw an error if the call wasn't successful
internal fun<E: kotlin.Exception> uniffiCheckCallStatus(errorHandler: UniffiRustCallStatusErrorHandler<E>, status: UniffiRustCallStatus) {
    if (status.isSuccess()) {
        return
    } else if (status.isError()) {
        throw errorHandler.lift(status.errorBuf)
    } else if (status.isPanic()) {
        // when the rust code sees a panic, it tries to construct a rustbuffer
        // with the message.  but if that code panics, then it just sends back
        // an empty buffer.
        if (status.errorBuf.len > 0) {
            throw InternalException(FfiConverterString.lift(status.errorBuf))
        } else {
            throw InternalException("Rust panic")
        }
    } else {
        throw InternalException("Unknown rust call status: $status.code")
    }
}

// UniffiRustCallStatusErrorHandler implementation for times when we don't expect a CALL_ERROR
public object UniffiNullRustCallStatusErrorHandler: UniffiRustCallStatusErrorHandler<InternalException> {
    override fun lift(errorBuf: RustBufferByValue): InternalException {
        RustBufferHelper.free(errorBuf)
        return InternalException("Unexpected CALL_ERROR")
    }
}

// Call a rust function that returns a plain value
internal inline fun <U> uniffiRustCall(crossinline callback: (UniffiRustCallStatus) -> U): U {
    return uniffiRustCallWithError(UniffiNullRustCallStatusErrorHandler, callback)
}

internal inline fun<T> uniffiTraitInterfaceCall(
    callStatus: UniffiRustCallStatus,
    makeCall: () -> T,
    writeReturn: (T) -> Unit,
) {
    try {
        writeReturn(makeCall())
    } catch(e: kotlin.Exception) {
        callStatus.code = UNIFFI_CALL_UNEXPECTED_ERROR
        callStatus.errorBuf = FfiConverterString.lower(e.toString())
    }
}

internal inline fun<T, reified E: Throwable> uniffiTraitInterfaceCallWithError(
    callStatus: UniffiRustCallStatus,
    makeCall: () -> T,
    writeReturn: (T) -> Unit,
    lowerError: (E) -> RustBufferByValue
) {
    try {
        writeReturn(makeCall())
    } catch(e: kotlin.Exception) {
        if (e is E) {
            callStatus.code = UNIFFI_CALL_ERROR
            callStatus.errorBuf = lowerError(e)
        } else {
            callStatus.code = UNIFFI_CALL_UNEXPECTED_ERROR
            callStatus.errorBuf = FfiConverterString.lower(e.toString())
        }
    }
}

@Structure.FieldOrder("code", "errorBuf")
internal open class UniffiRustCallStatusStruct(
    @JvmField public var code: Byte,
    @JvmField public var errorBuf: RustBufferByValue,
) : Structure() {
    internal constructor(): this(0.toByte(), RustBufferByValue())

    internal class ByValue(
        code: Byte,
        errorBuf: RustBufferByValue,
    ): UniffiRustCallStatusStruct(code, errorBuf), Structure.ByValue {
        internal constructor(): this(0.toByte(), RustBufferByValue())
    }
    internal class ByReference(
        code: Byte,
        errorBuf: RustBufferByValue,
    ): UniffiRustCallStatusStruct(code, errorBuf), Structure.ByReference {
        internal constructor(): this(0.toByte(), RustBufferByValue())
    }
}

internal typealias UniffiRustCallStatus = UniffiRustCallStatusStruct.ByReference
internal typealias UniffiRustCallStatusByValue = UniffiRustCallStatusStruct.ByValue

internal object UniffiRustCallStatusHelper {
    internal fun allocValue() = UniffiRustCallStatusByValue()
    internal fun <U> withReference(block: (UniffiRustCallStatus) -> U): U {
        val status = UniffiRustCallStatus()
        return block(status)
    }
}

internal class UniffiHandleMap<T: Any> {
    private val map = java.util.concurrent.ConcurrentHashMap<Long, T>()
    private val counter: kotlinx.atomicfu.AtomicLong = kotlinx.atomicfu.atomic(1L)

    internal val size: Int
        get() = map.size

    // Insert a new object into the handle map and get a handle for it
    internal fun insert(obj: T): Long {
        val handle = counter.getAndAdd(1)
        map[handle] = obj
        return handle
    }

    // Get an object from the handle map
    internal fun get(handle: Long): T {
        return map[handle] ?: throw InternalException("UniffiHandleMap.get: Invalid handle")
    }

    // Remove an entry from the handlemap and get the Kotlin object back
    internal fun remove(handle: Long): T {
        return map.remove(handle) ?: throw InternalException("UniffiHandleMap.remove: Invalid handle")
    }
}

internal typealias ByteByReference = com.sun.jna.ptr.ByteByReference
internal typealias DoubleByReference = com.sun.jna.ptr.DoubleByReference
internal typealias FloatByReference = com.sun.jna.ptr.FloatByReference
internal typealias IntByReference = com.sun.jna.ptr.IntByReference
internal typealias LongByReference = com.sun.jna.ptr.LongByReference
internal typealias PointerByReference = com.sun.jna.ptr.PointerByReference
internal typealias ShortByReference = com.sun.jna.ptr.ShortByReference

// Contains loading, initialization code,
// and the FFI Function declarations in a com.sun.jna.Library.

// Define FFI callback types
internal interface UniffiRustFutureContinuationCallback: com.sun.jna.Callback {
    public fun callback(`data`: Long,`pollResult`: Byte,)
}
internal interface UniffiForeignFutureFree: com.sun.jna.Callback {
    public fun callback(`handle`: Long,)
}
internal interface UniffiCallbackInterfaceFree: com.sun.jna.Callback {
    public fun callback(`handle`: Long,)
}
@Structure.FieldOrder("handle", "free")
internal open class UniffiForeignFutureStruct(
    @JvmField public var `handle`: Long,
    @JvmField public var `free`: UniffiForeignFutureFree?,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `handle` = 0.toLong(),
        
        `free` = null,
        
    )

    internal class UniffiByValue(
        `handle`: Long,
        `free`: UniffiForeignFutureFree?,
    ): UniffiForeignFuture(`handle`,`free`,), Structure.ByValue
}

internal typealias UniffiForeignFuture = UniffiForeignFutureStruct

internal fun UniffiForeignFuture.uniffiSetValue(other: UniffiForeignFuture) {
    `handle` = other.`handle`
    `free` = other.`free`
}
internal fun UniffiForeignFuture.uniffiSetValue(other: UniffiForeignFutureUniffiByValue) {
    `handle` = other.`handle`
    `free` = other.`free`
}

internal typealias UniffiForeignFutureUniffiByValue = UniffiForeignFutureStruct.UniffiByValue
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU8Struct(
    @JvmField public var `returnValue`: Byte,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toByte(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Byte,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructU8(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructU8 = UniffiForeignFutureStructU8Struct

internal fun UniffiForeignFutureStructU8.uniffiSetValue(other: UniffiForeignFutureStructU8) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU8.uniffiSetValue(other: UniffiForeignFutureStructU8UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU8UniffiByValue = UniffiForeignFutureStructU8Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteU8: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU8UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI8Struct(
    @JvmField public var `returnValue`: Byte,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toByte(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Byte,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructI8(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructI8 = UniffiForeignFutureStructI8Struct

internal fun UniffiForeignFutureStructI8.uniffiSetValue(other: UniffiForeignFutureStructI8) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI8.uniffiSetValue(other: UniffiForeignFutureStructI8UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI8UniffiByValue = UniffiForeignFutureStructI8Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteI8: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI8UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU16Struct(
    @JvmField public var `returnValue`: Short,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toShort(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Short,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructU16(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructU16 = UniffiForeignFutureStructU16Struct

internal fun UniffiForeignFutureStructU16.uniffiSetValue(other: UniffiForeignFutureStructU16) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU16.uniffiSetValue(other: UniffiForeignFutureStructU16UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU16UniffiByValue = UniffiForeignFutureStructU16Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteU16: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU16UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI16Struct(
    @JvmField public var `returnValue`: Short,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toShort(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Short,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructI16(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructI16 = UniffiForeignFutureStructI16Struct

internal fun UniffiForeignFutureStructI16.uniffiSetValue(other: UniffiForeignFutureStructI16) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI16.uniffiSetValue(other: UniffiForeignFutureStructI16UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI16UniffiByValue = UniffiForeignFutureStructI16Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteI16: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI16UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU32Struct(
    @JvmField public var `returnValue`: Int,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0,
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Int,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructU32(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructU32 = UniffiForeignFutureStructU32Struct

internal fun UniffiForeignFutureStructU32.uniffiSetValue(other: UniffiForeignFutureStructU32) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU32.uniffiSetValue(other: UniffiForeignFutureStructU32UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU32UniffiByValue = UniffiForeignFutureStructU32Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteU32: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU32UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI32Struct(
    @JvmField public var `returnValue`: Int,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0,
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Int,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructI32(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructI32 = UniffiForeignFutureStructI32Struct

internal fun UniffiForeignFutureStructI32.uniffiSetValue(other: UniffiForeignFutureStructI32) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI32.uniffiSetValue(other: UniffiForeignFutureStructI32UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI32UniffiByValue = UniffiForeignFutureStructI32Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteI32: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI32UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructU64Struct(
    @JvmField public var `returnValue`: Long,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toLong(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Long,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructU64(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructU64 = UniffiForeignFutureStructU64Struct

internal fun UniffiForeignFutureStructU64.uniffiSetValue(other: UniffiForeignFutureStructU64) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU64.uniffiSetValue(other: UniffiForeignFutureStructU64UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU64UniffiByValue = UniffiForeignFutureStructU64Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteU64: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructU64UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructI64Struct(
    @JvmField public var `returnValue`: Long,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.toLong(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Long,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructI64(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructI64 = UniffiForeignFutureStructI64Struct

internal fun UniffiForeignFutureStructI64.uniffiSetValue(other: UniffiForeignFutureStructI64) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI64.uniffiSetValue(other: UniffiForeignFutureStructI64UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI64UniffiByValue = UniffiForeignFutureStructI64Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteI64: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructI64UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructF32Struct(
    @JvmField public var `returnValue`: Float,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.0f,
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Float,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructF32(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructF32 = UniffiForeignFutureStructF32Struct

internal fun UniffiForeignFutureStructF32.uniffiSetValue(other: UniffiForeignFutureStructF32) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructF32.uniffiSetValue(other: UniffiForeignFutureStructF32UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructF32UniffiByValue = UniffiForeignFutureStructF32Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteF32: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructF32UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructF64Struct(
    @JvmField public var `returnValue`: Double,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = 0.0,
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Double,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructF64(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructF64 = UniffiForeignFutureStructF64Struct

internal fun UniffiForeignFutureStructF64.uniffiSetValue(other: UniffiForeignFutureStructF64) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructF64.uniffiSetValue(other: UniffiForeignFutureStructF64UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructF64UniffiByValue = UniffiForeignFutureStructF64Struct.UniffiByValue
internal interface UniffiForeignFutureCompleteF64: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructF64UniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructPointerStruct(
    @JvmField public var `returnValue`: Pointer?,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = NullPointer,
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: Pointer?,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructPointer(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructPointer = UniffiForeignFutureStructPointerStruct

internal fun UniffiForeignFutureStructPointer.uniffiSetValue(other: UniffiForeignFutureStructPointer) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructPointer.uniffiSetValue(other: UniffiForeignFutureStructPointerUniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructPointerUniffiByValue = UniffiForeignFutureStructPointerStruct.UniffiByValue
internal interface UniffiForeignFutureCompletePointer: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructPointerUniffiByValue,)
}
@Structure.FieldOrder("returnValue", "callStatus")
internal open class UniffiForeignFutureStructRustBufferStruct(
    @JvmField public var `returnValue`: RustBufferByValue,
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `returnValue` = RustBufferHelper.allocValue(),
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `returnValue`: RustBufferByValue,
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructRustBuffer(`returnValue`,`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructRustBuffer = UniffiForeignFutureStructRustBufferStruct

internal fun UniffiForeignFutureStructRustBuffer.uniffiSetValue(other: UniffiForeignFutureStructRustBuffer) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructRustBuffer.uniffiSetValue(other: UniffiForeignFutureStructRustBufferUniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructRustBufferUniffiByValue = UniffiForeignFutureStructRustBufferStruct.UniffiByValue
internal interface UniffiForeignFutureCompleteRustBuffer: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructRustBufferUniffiByValue,)
}
@Structure.FieldOrder("callStatus")
internal open class UniffiForeignFutureStructVoidStruct(
    @JvmField public var `callStatus`: UniffiRustCallStatusByValue,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `callStatus` = UniffiRustCallStatusHelper.allocValue(),
        
    )

    internal class UniffiByValue(
        `callStatus`: UniffiRustCallStatusByValue,
    ): UniffiForeignFutureStructVoid(`callStatus`,), Structure.ByValue
}

internal typealias UniffiForeignFutureStructVoid = UniffiForeignFutureStructVoidStruct

internal fun UniffiForeignFutureStructVoid.uniffiSetValue(other: UniffiForeignFutureStructVoid) {
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructVoid.uniffiSetValue(other: UniffiForeignFutureStructVoidUniffiByValue) {
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructVoidUniffiByValue = UniffiForeignFutureStructVoidStruct.UniffiByValue
internal interface UniffiForeignFutureCompleteVoid: com.sun.jna.Callback {
    public fun callback(`callbackData`: Long,`result`: UniffiForeignFutureStructVoidUniffiByValue,)
}
internal interface UniffiCallbackInterfacePairingCodesMethod0: com.sun.jna.Callback {
    public fun callback(`uniffiHandle`: Long,`uniffiFutureCallback`: UniffiForeignFutureCompleteRustBuffer,`uniffiCallbackData`: Long,`uniffiOutReturn`: UniffiForeignFuture,)
}
@Structure.FieldOrder("pairingCode", "uniffiFree")
internal open class UniffiVTableCallbackInterfacePairingCodesStruct(
    @JvmField public var `pairingCode`: UniffiCallbackInterfacePairingCodesMethod0?,
    @JvmField public var `uniffiFree`: UniffiCallbackInterfaceFree?,
) : com.sun.jna.Structure() {
    internal constructor(): this(
        
        `pairingCode` = null,
        
        `uniffiFree` = null,
        
    )

    internal class UniffiByValue(
        `pairingCode`: UniffiCallbackInterfacePairingCodesMethod0?,
        `uniffiFree`: UniffiCallbackInterfaceFree?,
    ): UniffiVTableCallbackInterfacePairingCodes(`pairingCode`,`uniffiFree`,), Structure.ByValue
}

internal typealias UniffiVTableCallbackInterfacePairingCodes = UniffiVTableCallbackInterfacePairingCodesStruct

internal fun UniffiVTableCallbackInterfacePairingCodes.uniffiSetValue(other: UniffiVTableCallbackInterfacePairingCodes) {
    `pairingCode` = other.`pairingCode`
    `uniffiFree` = other.`uniffiFree`
}
internal fun UniffiVTableCallbackInterfacePairingCodes.uniffiSetValue(other: UniffiVTableCallbackInterfacePairingCodesUniffiByValue) {
    `pairingCode` = other.`pairingCode`
    `uniffiFree` = other.`uniffiFree`
}

internal typealias UniffiVTableCallbackInterfacePairingCodesUniffiByValue = UniffiVTableCallbackInterfacePairingCodesStruct.UniffiByValue

































































































































@Synchronized
private fun findLibraryName(componentName: String): String {
    val libOverride = System.getProperty("uniffi.component.$componentName.libraryOverride")
    if (libOverride != null) {
        return libOverride
    }
    return "mobile_signer_ffi"
}

// For large crates we prevent `MethodTooLargeException` (see #2340)
// N.B. the name of the extension is very misleading, since it is 
// rather `InterfaceTooLargeException`, caused by too many methods 
// in the interface for large crates.
//
// By splitting the otherwise huge interface into two parts
// * UniffiLib 
// * IntegrityCheckingUniffiLib (this)
// we allow for ~2x as many methods in the UniffiLib interface.
// 
// The `ffi_uniffi_contract_version` method and all checksum methods are put 
// into `IntegrityCheckingUniffiLib` and these methods are called only once,
// when the library is loaded.
internal object IntegrityCheckingUniffiLib : Library {
    init {
        Native.register(IntegrityCheckingUniffiLib::class.java, findLibraryName("mobile_signer_ffi"))
        uniffiCheckContractApiVersion()
        uniffiCheckApiChecksums()
    }

    private fun uniffiCheckContractApiVersion() {
        // Get the bindings contract version from our ComponentInterface
        val bindingsContractVersion = 29
        // Get the scaffolding contract version by calling the into the dylib
        val scaffoldingContractVersion = ffi_mobile_signer_ffi_uniffi_contract_version()
        if (bindingsContractVersion != scaffoldingContractVersion) {
            throw RuntimeException("UniFFI contract version mismatch: try cleaning and rebuilding your project")
        }
    }
    private fun uniffiCheckApiChecksums() {
        if (uniffi_mobile_signer_ffi_checksum_func_bip39_last_word_candidates() != 28008.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_bip39_suggest() != 42278.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_check_mnemonic() != 19347.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_compute_vault_address() != 3897.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_compute_vault_address_multisig() != 45992.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_derive_borrower_pubkey() != 16439.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_fetch_btc_thb() != 42524.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_generate_software_multisig_key() != 291.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_broadcast() != 37288.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_fee_tiers() != 58197.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_forget_scan_state() != 8205.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_prepare_cancel() != 21487.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_prepare_fee_bump() != 12978.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_prepare_send_all() != 64839.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_prepare_send_at_rate() != 39001.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_receive_address() != 35083.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_tx_detail() != 25710.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_use_state_dir() != 46375.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_wallet_history() != 50495.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_hot_wallet_snapshot() != 59318.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_is_valid_address() != 44198.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_live_wallet_links() != 38905.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_restore_software_multisig_key() != 52876.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_scan_wallets() != 29092.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_func_sign_psbt() != 37769.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_method_pairingcodes_pairing_code() != 55814.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_method_walletlink_account_xpub() != 61385.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_method_walletlink_connect() != 15741.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_method_walletlink_disconnect() != 59990.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_method_walletlink_info() != 62108.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_method_walletlink_sign_psbt() != 56822.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_method_walletlink_unlock() != 26656.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
        if (uniffi_mobile_signer_ffi_checksum_constructor_walletlink_new() != 63717.toShort()) {
            throw RuntimeException("UniFFI API checksum mismatch: try cleaning and rebuilding your project")
        }
    }

    // Integrity check functions only
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_bip39_last_word_candidates(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_bip39_suggest(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_check_mnemonic(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_compute_vault_address(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_compute_vault_address_multisig(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_derive_borrower_pubkey(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_fetch_btc_thb(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_generate_software_multisig_key(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_broadcast(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_fee_tiers(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_forget_scan_state(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_prepare_cancel(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_prepare_fee_bump(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_prepare_send_all(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_prepare_send_at_rate(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_receive_address(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_tx_detail(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_use_state_dir(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_wallet_history(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_hot_wallet_snapshot(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_is_valid_address(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_live_wallet_links(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_restore_software_multisig_key(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_scan_wallets(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_func_sign_psbt(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_method_pairingcodes_pairing_code(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_method_walletlink_account_xpub(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_method_walletlink_connect(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_method_walletlink_disconnect(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_method_walletlink_info(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_method_walletlink_sign_psbt(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_method_walletlink_unlock(
    ): Short
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_checksum_constructor_walletlink_new(
    ): Short
    @JvmStatic
    external fun ffi_mobile_signer_ffi_uniffi_contract_version(
    ): Int
}

// A JNA Library to expose the extern-C FFI definitions.
// This is an implementation detail which will be called internally by the public API.
internal object UniffiLib : Library {

    init {
        IntegrityCheckingUniffiLib
        Native.register(UniffiLib::class.java, findLibraryName("mobile_signer_ffi"))
        // No need to check the contract version and checksums, since 
        // we already did that with `IntegrityCheckingUniffiLib` above.
        uniffiCallbackInterfacePairingCodes.register(this)
    }
    // The Cleaner for the whole library
    internal val CLEANER: UniffiCleaner by lazy {
        UniffiCleaner.create()
    }
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_clone_pairingcodes(
        `ptr`: Pointer?,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Pointer?
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_free_pairingcodes(
        `ptr`: Pointer?,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_init_callback_vtable_pairingcodes(
        `vtable`: UniffiVTableCallbackInterfacePairingCodes,
    ): Unit
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_method_pairingcodes_pairing_code(
        `ptr`: Pointer?,
    ): Long
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_clone_walletlink(
        `ptr`: Pointer?,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Pointer?
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_free_walletlink(
        `ptr`: Pointer?,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_constructor_walletlink_new(
        `wallet`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Pointer?
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_method_walletlink_account_xpub(
        `ptr`: Pointer?,
        `derivationPath`: RustBufferByValue,
    ): Long
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_method_walletlink_connect(
        `ptr`: Pointer?,
        `codes`: Pointer?,
    ): Long
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_method_walletlink_disconnect(
        `ptr`: Pointer?,
    ): Long
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_method_walletlink_info(
        `ptr`: Pointer?,
    ): Long
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_method_walletlink_sign_psbt(
        `ptr`: Pointer?,
        `psbtBase64`: RustBufferByValue,
        `signerFingerprint`: RustBufferByValue,
    ): Long
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_method_walletlink_unlock(
        `ptr`: Pointer?,
    ): Long
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_bip39_last_word_candidates(
        `firstWords`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_bip39_suggest(
        `prefix`: RustBufferByValue,
        `limit`: Int,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_check_mnemonic(
        `words`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_compute_vault_address(
        `borrowerPubkey`: RustBufferByValue,
        `platformPubkey`: RustBufferByValue,
        `lenderPubkey`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_compute_vault_address_multisig(
        `xpubs`: RustBufferByValue,
        `threshold`: Byte,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_derive_borrower_pubkey(
        `accountXpub`: RustBufferByValue,
        `index`: Int,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_fetch_btc_thb(
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_generate_software_multisig_key(
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `account`: Int,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_broadcast(
        `network`: RustBufferByValue,
        `hex`: RustBufferByValue,
        `txid`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_fee_tiers(
        `network`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_forget_scan_state(
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_prepare_cancel(
        `mnemonicWords`: RustBufferByValue,
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `addressType`: RustBufferByValue,
        `earlierHex`: RustBufferByValue,
        `feeRateSatVb`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_prepare_fee_bump(
        `mnemonicWords`: RustBufferByValue,
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `addressType`: RustBufferByValue,
        `earlierHex`: RustBufferByValue,
        `feeRateSatVb`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_prepare_send_all(
        `mnemonicWords`: RustBufferByValue,
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `addressType`: RustBufferByValue,
        `destination`: RustBufferByValue,
        `feeRateSatVb`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_prepare_send_at_rate(
        `mnemonicWords`: RustBufferByValue,
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `addressType`: RustBufferByValue,
        `destination`: RustBufferByValue,
        `amountSats`: Long,
        `feeRateSatVb`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_receive_address(
        `mnemonicWords`: RustBufferByValue,
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `addressType`: RustBufferByValue,
        `index`: Int,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_tx_detail(
        `mnemonicWords`: RustBufferByValue,
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `addressType`: RustBufferByValue,
        `txid`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_use_state_dir(
        `dir`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_wallet_history(
        `mnemonicWords`: RustBufferByValue,
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `addressType`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_hot_wallet_snapshot(
        `mnemonicWords`: RustBufferByValue,
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `addressType`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_is_valid_address(
        `address`: RustBufferByValue,
        `network`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Byte
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_live_wallet_links(
        uniffiCallStatus: UniffiRustCallStatus,
    ): Long
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_restore_software_multisig_key(
        `mnemonicWords`: RustBufferByValue,
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `account`: Int,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_scan_wallets(
        `kind`: RustBufferByValue,
    ): Long
    @JvmStatic
    external fun uniffi_mobile_signer_ffi_fn_func_sign_psbt(
        `psbtJson`: RustBufferByValue,
        `borrowerPubkey`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rustbuffer_alloc(
        `size`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rustbuffer_from_bytes(
        `bytes`: ForeignBytesByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rustbuffer_free(
        `buf`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rustbuffer_reserve(
        `buf`: RustBufferByValue,
        `additional`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_u8(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_u8(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_u8(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_u8(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Byte
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_i8(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_i8(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_i8(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_i8(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Byte
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_u16(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_u16(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_u16(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_u16(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Short
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_i16(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_i16(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_i16(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_i16(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Short
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_u32(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_u32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_u32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_u32(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Int
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_i32(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_i32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_i32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_i32(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Int
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_u64(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_u64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_u64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_u64(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Long
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_i64(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_i64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_i64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_i64(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Long
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_f32(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_f32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_f32(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_f32(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Float
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_f64(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_f64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_f64(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_f64(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Double
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_pointer(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_pointer(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_pointer(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_pointer(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Pointer?
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_rust_buffer(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_rust_buffer(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_rust_buffer(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_rust_buffer(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_poll_void(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_cancel_void(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_free_void(
        `handle`: Long,
    ): Unit
    @JvmStatic
    external fun ffi_mobile_signer_ffi_rust_future_complete_void(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit
}

public fun uniffiEnsureInitialized() {
    UniffiLib
}

// Public interface members begin here.

internal const val IDX_CALLBACK_FREE = 0
// Callback return codes
internal const val UNIFFI_CALLBACK_SUCCESS = 0
internal const val UNIFFI_CALLBACK_ERROR = 1
internal const val UNIFFI_CALLBACK_UNEXPECTED_ERROR = 2

public abstract class FfiConverterCallbackInterface<CallbackInterface: Any>: FfiConverter<CallbackInterface, Long> {
    internal val handleMap = UniffiHandleMap<CallbackInterface>()

    internal fun drop(handle: Long) {
        handleMap.remove(handle)
    }

    override fun lift(value: Long): CallbackInterface {
        return handleMap.get(value)
    }

    override fun read(buf: ByteBuffer): CallbackInterface = lift(buf.getLong())

    override fun lower(value: CallbackInterface): Long = handleMap.insert(value)

    override fun allocationSize(value: CallbackInterface): ULong = 8UL

    override fun write(value: CallbackInterface, buf: ByteBuffer) {
        buf.putLong(lower(value))
    }
}
// The cleaner interface for Object finalization code to run.
// This is the entry point to any implementation that we're using.
//
// The cleaner registers disposables and returns cleanables, so now we are
// defining a `UniffiCleaner` with a `UniffiClenaer.Cleanable` to abstract the
// different implementations available at compile time.
public interface UniffiCleaner {
    public interface Cleanable {
        public fun clean()
    }

    public fun register(resource: Any, disposable: Disposable): UniffiCleaner.Cleanable

    public companion object
}
// The fallback Jna cleaner, which is available for both Android, and the JVM.
private class UniffiJnaCleaner : UniffiCleaner {
    private val cleaner = com.sun.jna.internal.Cleaner.getCleaner()

    override fun register(resource: Any, disposable: Disposable): UniffiCleaner.Cleanable =
        UniffiJnaCleanable(cleaner.register(resource, UniffiCleanerAction(disposable)))
}

private class UniffiJnaCleanable(
    private val cleanable: com.sun.jna.internal.Cleaner.Cleanable,
) : UniffiCleaner.Cleanable {
    override fun clean() = cleanable.clean()
}

private class UniffiCleanerAction(private val disposable: Disposable): Runnable {
    override fun run() {
        disposable.destroy()
    }
}

// The SystemCleaner, available from API Level 33.
// Some API Level 33 OSes do not support using it, so we require API Level 34.
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class AndroidSystemCleaner : UniffiCleaner {
    private val cleaner = android.system.SystemCleaner.cleaner()

    override fun register(resource: Any, disposable: Disposable): UniffiCleaner.Cleanable =
        AndroidSystemCleanable(cleaner.register(resource, UniffiCleanerAction(disposable)))
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class AndroidSystemCleanable(
    private val cleanable: java.lang.ref.Cleaner.Cleanable,
) : UniffiCleaner.Cleanable {
    override fun clean() = cleanable.clean()
}

private fun UniffiCleaner.Companion.create(): UniffiCleaner {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        try {
            return AndroidSystemCleaner()
        } catch (_: IllegalAccessError) {
            // (For Compose preview) Fallback to UniffiJnaCleaner if AndroidSystemCleaner is
            // unavailable, even for API level 34 or higher.
        }
    }
    return UniffiJnaCleaner()
}


public object FfiConverterUByte: FfiConverter<UByte, Byte> {
    override fun lift(value: Byte): UByte {
        return value.toUByte()
    }

    override fun read(buf: ByteBuffer): UByte {
        return lift(buf.get())
    }

    override fun lower(value: UByte): Byte {
        return value.toByte()
    }

    override fun allocationSize(value: UByte): ULong = 1UL

    override fun write(value: UByte, buf: ByteBuffer) {
        buf.put(value.toByte())
    }
}


public object FfiConverterUShort: FfiConverter<UShort, Short> {
    override fun lift(value: Short): UShort {
        return value.toUShort()
    }

    override fun read(buf: ByteBuffer): UShort {
        return lift(buf.getShort())
    }

    override fun lower(value: UShort): Short {
        return value.toShort()
    }

    override fun allocationSize(value: UShort): ULong = 2UL

    override fun write(value: UShort, buf: ByteBuffer) {
        buf.putShort(value.toShort())
    }
}


public object FfiConverterUInt: FfiConverter<UInt, Int> {
    override fun lift(value: Int): UInt {
        return value.toUInt()
    }

    override fun read(buf: ByteBuffer): UInt {
        return lift(buf.getInt())
    }

    override fun lower(value: UInt): Int {
        return value.toInt()
    }

    override fun allocationSize(value: UInt): ULong = 4UL

    override fun write(value: UInt, buf: ByteBuffer) {
        buf.putInt(value.toInt())
    }
}


public object FfiConverterULong: FfiConverter<ULong, Long> {
    override fun lift(value: Long): ULong {
        return value.toULong()
    }

    override fun read(buf: ByteBuffer): ULong {
        return lift(buf.getLong())
    }

    override fun lower(value: ULong): Long {
        return value.toLong()
    }

    override fun allocationSize(value: ULong): ULong = 8UL

    override fun write(value: ULong, buf: ByteBuffer) {
        buf.putLong(value.toLong())
    }
}


public object FfiConverterLong: FfiConverter<Long, Long> {
    override fun lift(value: Long): Long {
        return value
    }

    override fun read(buf: ByteBuffer): Long {
        return buf.getLong()
    }

    override fun lower(value: Long): Long {
        return value
    }

    override fun allocationSize(value: Long): ULong = 8UL

    override fun write(value: Long, buf: ByteBuffer) {
        buf.putLong(value)
    }
}


public object FfiConverterDouble: FfiConverter<Double, Double> {
    override fun lift(value: Double): Double {
        return value
    }

    override fun read(buf: ByteBuffer): Double {
        return buf.getDouble()
    }

    override fun lower(value: Double): Double {
        return value
    }

    override fun allocationSize(value: Double): ULong = 8UL

    override fun write(value: Double, buf: ByteBuffer) {
        buf.putDouble(value)
    }
}


public object FfiConverterBoolean: FfiConverter<Boolean, Byte> {
    override fun lift(value: Byte): Boolean {
        return value.toInt() != 0
    }

    override fun read(buf: ByteBuffer): Boolean {
        return lift(buf.get())
    }

    override fun lower(value: Boolean): Byte {
        return if (value) 1.toByte() else 0.toByte()
    }

    override fun allocationSize(value: Boolean): ULong = 1UL

    override fun write(value: Boolean, buf: ByteBuffer) {
        buf.put(lower(value))
    }
}


public object FfiConverterString: FfiConverter<String, RustBufferByValue> {
    // Note: we don't inherit from FfiConverterRustBuffer, because we use a
    // special encoding when lowering/lifting.  We can use `RustBuffer.len` to
    // store our length and avoid writing it out to the buffer.
    override fun lift(value: RustBufferByValue): String {
        try {
            require(value.len <= Int.MAX_VALUE) {
        val length = value.len
        "cannot handle RustBuffer longer than Int.MAX_VALUE bytes: length is $length"
    }
            val byteArr =  value.asByteBuffer()!!.get(value.len.toInt())
            return byteArr.decodeToString()
        } finally {
            RustBufferHelper.free(value)
        }
    }

    override fun read(buf: ByteBuffer): String {
        val len = buf.getInt()
        val byteArr = buf.get(len)
        return byteArr.decodeToString()
    }

    override fun lower(value: String): RustBufferByValue {
        // TODO: prevent allocating a new byte array here
        val encoded = value.encodeToByteArray(throwOnInvalidSequence = true)
        return RustBufferHelper.allocValue(encoded.size.toULong()).apply {
            asByteBuffer()!!.put(encoded)
        }
    }

    // We aren't sure exactly how many bytes our string will be once it's UTF-8
    // encoded.  Allocate 3 bytes per UTF-16 code unit which will always be
    // enough.
    override fun allocationSize(value: String): ULong {
        val sizeForLength = 4UL
        val sizeForString = value.length.toULong() * 3UL
        return sizeForLength + sizeForString
    }

    override fun write(value: String, buf: ByteBuffer) {
        // TODO: prevent allocating a new byte array here
        val encoded = value.encodeToByteArray(throwOnInvalidSequence = true)
        buf.putInt(encoded.size)
        buf.put(encoded)
    }
}



/**
 * Asks the user for the six digits a Trezor Safe 7 shows while pairing (not its PIN).
 * Return null if the user gives up, and never throw: an exception cannot be mapped, so the
 * connect fails with `InternalException`. It may be asked again on one connect when an answer
 * is not six digits. A Jade never asks.
 */
public actual open class PairingCodesImpl: Disposable, PairingCodes {

    public constructor(pointer: Pointer) {
        this.pointer = pointer
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiPointerDestroyer(pointer))
    }

    /**
     * This constructor can be used to instantiate a fake object. Only used for tests. Any
     * attempt to actually use an object constructed this way will fail as there is no
     * connected Rust object.
     */
    public actual constructor(noPointer: NoPointer) {
        this.pointer = null
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiPointerDestroyer(null))
    }

    protected val pointer: Pointer?
    protected val cleanable: UniffiCleaner.Cleanable

    private val wasDestroyed: kotlinx.atomicfu.AtomicBoolean = kotlinx.atomicfu.atomic(false)
    private val callCounter: kotlinx.atomicfu.AtomicLong = kotlinx.atomicfu.atomic(1L)

    private val lock = kotlinx.atomicfu.locks.ReentrantLock()

    private fun <T> synchronized(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    actual override fun destroy() {
        // Only allow a single call to this method.
        // TODO: maybe we should log a warning if called more than once?
        if (this.wasDestroyed.compareAndSet(false, true)) {
            // This decrement always matches the initial count of 1 given at creation time.
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    actual override fun close() {
        synchronized { this.destroy() }
    }

    internal inline fun <R> callWithPointer(block: (ptr: Pointer) -> R): R {
        // Check and increment the call counter, to keep the object alive.
        // This needs a compare-and-set retry loop in case of concurrent updates.
        do {
            val c = this.callCounter.value
            if (c == 0L) {
                throw IllegalStateException("${this::class::simpleName} object has already been destroyed")
            }
            if (c == Long.MAX_VALUE) {
                throw IllegalStateException("${this::class::simpleName} call counter would overflow")
            }
        } while (! this.callCounter.compareAndSet(c, c + 1L))
        // Now we can safely do the method call without the pointer being freed concurrently.
        try {
            return block(this.uniffiClonePointer())
        } finally {
            // This decrement always matches the increment we performed above.
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    // Use a static inner class instead of a closure so as not to accidentally
    // capture `this` as part of the cleanable's action.
    private class UniffiPointerDestroyer(private val pointer: Pointer?) : Disposable {
        override fun destroy() {
            pointer?.let { ptr ->
                uniffiRustCall { status ->
                    UniffiLib.uniffi_mobile_signer_ffi_fn_free_pairingcodes(ptr, status)
                }
            }
        }
    }

    public fun uniffiClonePointer(): Pointer {
        return uniffiRustCall { status ->
            UniffiLib.uniffi_mobile_signer_ffi_fn_clone_pairingcodes(pointer!!, status)
        }!!
    }

    
    public actual override suspend fun `pairingCode`(): kotlin.String? {
        return uniffiRustCallAsync(
            callWithPointer { thisPtr ->
                UniffiLib.uniffi_mobile_signer_ffi_fn_method_pairingcodes_pairing_code(
                    thisPtr,
                )
            },
            { future, callback, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_poll_rust_buffer(future, callback, continuation) },
            { future, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_complete_rust_buffer(future, continuation) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_free_rust_buffer(future) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_cancel_rust_buffer(future) },
            // lift function
            { FfiConverterOptionalString.lift(it) },
            // Error FFI converter
            UniffiNullRustCallStatusErrorHandler,
        )
    }


    
    
    public actual companion object
    
}





public object FfiConverterTypePairingCodes: FfiConverter<PairingCodes, Pointer> {
    internal val handleMap = UniffiHandleMap<PairingCodes>()

    override fun lower(value: PairingCodes): Pointer {
        return handleMap.insert(value).toPointer()
    }

    override fun lift(value: Pointer): PairingCodes {
        return PairingCodesImpl(value)
    }

    override fun read(buf: ByteBuffer): PairingCodes {
        // The Rust code always writes pointers as 8 bytes, and will
        // fail to compile if they don't fit.
        return lift(buf.getLong().toPointer())
    }

    override fun allocationSize(value: PairingCodes): ULong = 8UL

    override fun write(value: PairingCodes, buf: ByteBuffer) {
        // The Rust code always expects pointers written as 8 bytes,
        // and will fail to compile if they don't fit.
        buf.putLong(lower(value).toLong())
    }
}


// Put the implementation in an object so we don't pollute the top-level namespace
internal object uniffiCallbackInterfacePairingCodes {
    internal object `pairingCode`: UniffiCallbackInterfacePairingCodesMethod0 {
        override fun callback (
            `uniffiHandle`: Long,
            `uniffiFutureCallback`: UniffiForeignFutureCompleteRustBuffer,
            `uniffiCallbackData`: Long,
            `uniffiOutReturn`: UniffiForeignFuture,
        ) {
            val uniffiObj = FfiConverterTypePairingCodes.handleMap.get(uniffiHandle)
            val makeCall = suspend { ->
                uniffiObj.`pairingCode`(
                )
            }
            val uniffiHandleSuccess = { returnValue: kotlin.String? ->
                val uniffiResult = UniffiForeignFutureStructRustBufferUniffiByValue(
                    FfiConverterOptionalString.lower(returnValue),
                    UniffiRustCallStatusHelper.allocValue()
                )
                uniffiResult.write()
                uniffiFutureCallback.callback(uniffiCallbackData, uniffiResult)
            }
            val uniffiHandleError = { callStatus: UniffiRustCallStatusByValue ->
                uniffiFutureCallback.callback(
                    uniffiCallbackData,
                    UniffiForeignFutureStructRustBufferUniffiByValue(
                        RustBufferHelper.allocValue(),
                        callStatus,
                    ),
                )
            }

            uniffiOutReturn.uniffiSetValue(
                uniffiTraitInterfaceCallAsync(
                    makeCall,
                    uniffiHandleSuccess,
                    uniffiHandleError
                )
            )
        }
    }
    internal object uniffiFree: UniffiCallbackInterfaceFree {
        override fun callback(handle: Long) {
            FfiConverterTypePairingCodes.handleMap.remove(handle)
        }
    }

    internal val vtable = UniffiVTableCallbackInterfacePairingCodes(
        `pairingCode`,
        uniffiFree,
    )

    internal fun register(lib: UniffiLib) {
        lib.uniffi_mobile_signer_ffi_fn_init_callback_vtable_pairingcodes(vtable)
    }
}



/**
 * A link to one wallet that `scan_wallets` found. Make it, call `connect`, close it when done.
 *
 * A task owns the device and this object sends it requests, so a cancelled call never leaves
 * the device half-way through an exchange. Closing this object starts the disconnect, once no
 * call is still running on it, and returns before the link is down: cancel the call first, and
 * use `disconnect()` to wait. A link connects once: after a failed or cancelled `connect`,
 * make a new one.
 *
 * A call made on a coroutine that is already cancelled throws before it starts and leaves
 * nothing behind, so `close()` still releases the link. Wrap a call that must run after a
 * cancel, such as `disconnect()` to wait for the link to be down, in `withContext(NonCancellable)`.
 */
public actual open class WalletLink: Disposable, WalletLinkInterface {

    public constructor(pointer: Pointer) {
        this.pointer = pointer
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiPointerDestroyer(pointer))
    }

    /**
     * This constructor can be used to instantiate a fake object. Only used for tests. Any
     * attempt to actually use an object constructed this way will fail as there is no
     * connected Rust object.
     */
    public actual constructor(noPointer: NoPointer) {
        this.pointer = null
        this.cleanable = UniffiLib.CLEANER.register(this, UniffiPointerDestroyer(null))
    }
    /**
     * Makes a link that is not connected yet. It exists before `connect` starts, so a
     * `connect` cancelled at any moment leaves nothing that cannot be closed.
     */
    public actual constructor(`wallet`: FoundWallet) : this(
        uniffiRustCall { uniffiRustCallStatus ->
            UniffiLib.uniffi_mobile_signer_ffi_fn_constructor_walletlink_new(
                FfiConverterTypeFoundWallet.lower(`wallet`),
                uniffiRustCallStatus,
            )
        }!!
    )

    protected val pointer: Pointer?
    protected val cleanable: UniffiCleaner.Cleanable

    private val wasDestroyed: kotlinx.atomicfu.AtomicBoolean = kotlinx.atomicfu.atomic(false)
    private val callCounter: kotlinx.atomicfu.AtomicLong = kotlinx.atomicfu.atomic(1L)

    private val lock = kotlinx.atomicfu.locks.ReentrantLock()

    private fun <T> synchronized(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    actual override fun destroy() {
        // Only allow a single call to this method.
        // TODO: maybe we should log a warning if called more than once?
        if (this.wasDestroyed.compareAndSet(false, true)) {
            // This decrement always matches the initial count of 1 given at creation time.
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    actual override fun close() {
        synchronized { this.destroy() }
    }

    internal inline fun <R> callWithPointer(block: (ptr: Pointer) -> R): R {
        // Check and increment the call counter, to keep the object alive.
        // This needs a compare-and-set retry loop in case of concurrent updates.
        do {
            val c = this.callCounter.value
            if (c == 0L) {
                throw IllegalStateException("${this::class::simpleName} object has already been destroyed")
            }
            if (c == Long.MAX_VALUE) {
                throw IllegalStateException("${this::class::simpleName} call counter would overflow")
            }
        } while (! this.callCounter.compareAndSet(c, c + 1L))
        // Now we can safely do the method call without the pointer being freed concurrently.
        try {
            return block(this.uniffiClonePointer())
        } finally {
            // This decrement always matches the increment we performed above.
            if (this.callCounter.decrementAndGet() == 0L) {
                cleanable.clean()
            }
        }
    }

    // Use a static inner class instead of a closure so as not to accidentally
    // capture `this` as part of the cleanable's action.
    private class UniffiPointerDestroyer(private val pointer: Pointer?) : Disposable {
        override fun destroy() {
            pointer?.let { ptr ->
                uniffiRustCall { status ->
                    UniffiLib.uniffi_mobile_signer_ffi_fn_free_walletlink(ptr, status)
                }
            }
        }
    }

    public fun uniffiClonePointer(): Pointer {
        return uniffiRustCall { status ->
            UniffiLib.uniffi_mobile_signer_ffi_fn_clone_walletlink(pointer!!, status)
        }!!
    }

    
    /**
     * The account extended public key at `derivation_path`.
     *
     * `derivation_path` is a BIP-32 path such as `m/48'/1'/0'/2'`. An unreadable
     * path is refused and the link stays up. A Jade must be unlocked first.
     * The master fingerprint is 8 lowercase hex characters. Cancelling stops
     * only the wait. A refusal leaves the link up.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public actual override suspend fun `accountXpub`(`derivationPath`: kotlin.String): HwAccountKey {
        return uniffiRustCallAsync(
            callWithPointer { thisPtr ->
                UniffiLib.uniffi_mobile_signer_ffi_fn_method_walletlink_account_xpub(
                    thisPtr,
                    FfiConverterString.lower(`derivationPath`),
                )
            },
            { future, callback, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_poll_rust_buffer(future, callback, continuation) },
            { future, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_complete_rust_buffer(future, continuation) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_free_rust_buffer(future) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_cancel_rust_buffer(future) },
            // lift function
            { FfiConverterTypeHwAccountKey.lift(it) },
            // Error FFI converter
            BleExceptionErrorHandler,
        )
    }

    /**
     * Connects to the wallet, pairs if it must, and checks the wallet is what it says.
     * Cancelling while it runs leaves nothing that closing the link cannot end: measured with a
     * Jade, unit-tested with a fake central for a Safe 7. A Jade that fails disconnects in the
     * background, a Safe 7 before this returns.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public actual override suspend fun `connect`(`codes`: PairingCodes) {
        return uniffiRustCallAsync(
            callWithPointer { thisPtr ->
                UniffiLib.uniffi_mobile_signer_ffi_fn_method_walletlink_connect(
                    thisPtr,
                    FfiConverterTypePairingCodes.lower(`codes`),
                )
            },
            { future, callback, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_poll_void(future, callback, continuation) },
            { future, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_complete_void(future, continuation) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_free_void(future) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_cancel_void(future) },
            // lift function
            { Unit },
            
            // Error FFI converter
            BleExceptionErrorHandler,
        )
    }

    /**
     * Ends the link and waits until it is down, also when a failure or another call is
     * already ending it. A request in flight finishes first. It does nothing until `connect`
     * has returned: cancel a `connect` that is still running instead.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public actual override suspend fun `disconnect`() {
        return uniffiRustCallAsync(
            callWithPointer { thisPtr ->
                UniffiLib.uniffi_mobile_signer_ffi_fn_method_walletlink_disconnect(
                    thisPtr,
                )
            },
            { future, callback, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_poll_void(future, callback, continuation) },
            { future, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_complete_void(future, continuation) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_free_void(future) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_cancel_void(future) },
            // lift function
            { Unit },
            
            // Error FFI converter
            BleExceptionErrorHandler,
        )
    }

    /**
     * Name, firmware and what only this vendor reports. Cancelling stops only the wait:
     * the wallet finishes the exchange and the link stays usable.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public actual override suspend fun `info`(): WalletInfo {
        return uniffiRustCallAsync(
            callWithPointer { thisPtr ->
                UniffiLib.uniffi_mobile_signer_ffi_fn_method_walletlink_info(
                    thisPtr,
                )
            },
            { future, callback, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_poll_rust_buffer(future, callback, continuation) },
            { future, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_complete_rust_buffer(future, continuation) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_free_rust_buffer(future) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_cancel_rust_buffer(future) },
            // lift function
            { FfiConverterTypeWalletInfo.lift(it) },
            // Error FFI converter
            BleExceptionErrorHandler,
        )
    }

    /**
     * Has the hardware wallet sign `psbt_base64` for `signer_fingerprint`.
     *
     * `psbt_base64` is a BIP-174 PSBT. `signer_fingerprint` is 8 lowercase hex
     * characters (the vault key's master fingerprint). Jade must be unlocked
     * first. Returns the signed PSBT as base64. Callers must run spend policy
     * before this (vault-core policy against real PSBTs is still Term-2 work;
     * the app checks destination and amount at the UI boundary today).
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public actual override suspend fun `signPsbt`(`psbtBase64`: kotlin.String, `signerFingerprint`: kotlin.String): kotlin.String {
        return uniffiRustCallAsync(
            callWithPointer { thisPtr ->
                UniffiLib.uniffi_mobile_signer_ffi_fn_method_walletlink_sign_psbt(
                    thisPtr,
                    FfiConverterString.lower(`psbtBase64`),
                    FfiConverterString.lower(`signerFingerprint`),
                )
            },
            { future, callback, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_poll_rust_buffer(future, callback, continuation) },
            { future, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_complete_rust_buffer(future, continuation) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_free_rust_buffer(future) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_cancel_rust_buffer(future) },
            // lift function
            { FfiConverterString.lift(it) },
            // Error FFI converter
            BleExceptionErrorHandler,
        )
    }

    /**
     * Unlocks the wallet for this connection.
     *
     * A Jade asks for its PIN on the device, unless it is a Temporary Signer.
     * A Safe 7 has no separate unlock. Connect already opened the session, so
     * this returns immediately. Cancelling stops only the wait. A refusal
     * leaves the link up.
     */
    @Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
    public actual override suspend fun `unlock`() {
        return uniffiRustCallAsync(
            callWithPointer { thisPtr ->
                UniffiLib.uniffi_mobile_signer_ffi_fn_method_walletlink_unlock(
                    thisPtr,
                )
            },
            { future, callback, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_poll_void(future, callback, continuation) },
            { future, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_complete_void(future, continuation) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_free_void(future) },
            { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_cancel_void(future) },
            // lift function
            { Unit },
            
            // Error FFI converter
            BleExceptionErrorHandler,
        )
    }


    
    
    public actual companion object
    
}





public object FfiConverterTypeWalletLink: FfiConverter<WalletLink, Pointer> {

    override fun lower(value: WalletLink): Pointer {
        return value.uniffiClonePointer()
    }

    override fun lift(value: Pointer): WalletLink {
        return WalletLink(value)
    }

    override fun read(buf: ByteBuffer): WalletLink {
        // The Rust code always writes pointers as 8 bytes, and will
        // fail to compile if they don't fit.
        return lift(buf.getLong().toPointer())
    }

    override fun allocationSize(value: WalletLink): ULong = 8UL

    override fun write(value: WalletLink, buf: ByteBuffer) {
        // The Rust code always expects pointers written as 8 bytes,
        // and will fail to compile if they don't fit.
        buf.putLong(lower(value).toLong())
    }
}




public object FfiConverterTypeBtcPrice: FfiConverterRustBuffer<BtcPrice> {
    override fun read(buf: ByteBuffer): BtcPrice {
        return BtcPrice(
            FfiConverterDouble.read(buf),
            FfiConverterULong.read(buf),
        )
    }

    override fun allocationSize(value: BtcPrice): ULong = (
            FfiConverterDouble.allocationSize(value.`thbPerBtc`) +
            FfiConverterULong.allocationSize(value.`fetchedAt`)
    )

    override fun write(value: BtcPrice, buf: ByteBuffer) {
        FfiConverterDouble.write(value.`thbPerBtc`, buf)
        FfiConverterULong.write(value.`fetchedAt`, buf)
    }
}




public object FfiConverterTypeFeeTiers: FfiConverterRustBuffer<FeeTiers> {
    override fun read(buf: ByteBuffer): FeeTiers {
        return FeeTiers(
            FfiConverterULong.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterBoolean.read(buf),
        )
    }

    override fun allocationSize(value: FeeTiers): ULong = (
            FfiConverterULong.allocationSize(value.`slowSatVb`) +
            FfiConverterULong.allocationSize(value.`normalSatVb`) +
            FfiConverterULong.allocationSize(value.`fastSatVb`) +
            FfiConverterBoolean.allocationSize(value.`live`)
    )

    override fun write(value: FeeTiers, buf: ByteBuffer) {
        FfiConverterULong.write(value.`slowSatVb`, buf)
        FfiConverterULong.write(value.`normalSatVb`, buf)
        FfiConverterULong.write(value.`fastSatVb`, buf)
        FfiConverterBoolean.write(value.`live`, buf)
    }
}




public object FfiConverterTypeFoundWallet: FfiConverterRustBuffer<FoundWallet> {
    override fun read(buf: ByteBuffer): FoundWallet {
        return FoundWallet(
            FfiConverterTypeWalletKind.read(buf),
            FfiConverterString.read(buf),
            FfiConverterString.read(buf),
        )
    }

    override fun allocationSize(value: FoundWallet): ULong = (
            FfiConverterTypeWalletKind.allocationSize(value.`kind`) +
            FfiConverterString.allocationSize(value.`id`) +
            FfiConverterString.allocationSize(value.`name`)
    )

    override fun write(value: FoundWallet, buf: ByteBuffer) {
        FfiConverterTypeWalletKind.write(value.`kind`, buf)
        FfiConverterString.write(value.`id`, buf)
        FfiConverterString.write(value.`name`, buf)
    }
}




public object FfiConverterTypeHotSendResult: FfiConverterRustBuffer<HotSendResult> {
    override fun read(buf: ByteBuffer): HotSendResult {
        return HotSendResult(
            FfiConverterString.read(buf),
        )
    }

    override fun allocationSize(value: HotSendResult): ULong = (
            FfiConverterString.allocationSize(value.`txid`)
    )

    override fun write(value: HotSendResult, buf: ByteBuffer) {
        FfiConverterString.write(value.`txid`, buf)
    }
}




public object FfiConverterTypeHotTx: FfiConverterRustBuffer<HotTx> {
    override fun read(buf: ByteBuffer): HotTx {
        return HotTx(
            FfiConverterString.read(buf),
            FfiConverterLong.read(buf),
            FfiConverterUInt.read(buf),
            FfiConverterOptionalULong.read(buf),
        )
    }

    override fun allocationSize(value: HotTx): ULong = (
            FfiConverterString.allocationSize(value.`txid`) +
            FfiConverterLong.allocationSize(value.`netSats`) +
            FfiConverterUInt.allocationSize(value.`confirmations`) +
            FfiConverterOptionalULong.allocationSize(value.`blockTime`)
    )

    override fun write(value: HotTx, buf: ByteBuffer) {
        FfiConverterString.write(value.`txid`, buf)
        FfiConverterLong.write(value.`netSats`, buf)
        FfiConverterUInt.write(value.`confirmations`, buf)
        FfiConverterOptionalULong.write(value.`blockTime`, buf)
    }
}




public object FfiConverterTypeHotTxDetail: FfiConverterRustBuffer<HotTxDetail> {
    override fun read(buf: ByteBuffer): HotTxDetail {
        return HotTxDetail(
            FfiConverterTypeHotTx.read(buf),
            FfiConverterOptionalULong.read(buf),
            FfiConverterSequenceTypeHotTxo.read(buf),
            FfiConverterSequenceTypeHotTxo.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterBoolean.read(buf),
        )
    }

    override fun allocationSize(value: HotTxDetail): ULong = (
            FfiConverterTypeHotTx.allocationSize(value.`tx`) +
            FfiConverterOptionalULong.allocationSize(value.`blockHeight`) +
            FfiConverterSequenceTypeHotTxo.allocationSize(value.`inputs`) +
            FfiConverterSequenceTypeHotTxo.allocationSize(value.`outputs`) +
            FfiConverterULong.allocationSize(value.`feeSats`) +
            FfiConverterULong.allocationSize(value.`sizeBytes`) +
            FfiConverterULong.allocationSize(value.`vsize`) +
            FfiConverterBoolean.allocationSize(value.`signalsRbf`)
    )

    override fun write(value: HotTxDetail, buf: ByteBuffer) {
        FfiConverterTypeHotTx.write(value.`tx`, buf)
        FfiConverterOptionalULong.write(value.`blockHeight`, buf)
        FfiConverterSequenceTypeHotTxo.write(value.`inputs`, buf)
        FfiConverterSequenceTypeHotTxo.write(value.`outputs`, buf)
        FfiConverterULong.write(value.`feeSats`, buf)
        FfiConverterULong.write(value.`sizeBytes`, buf)
        FfiConverterULong.write(value.`vsize`, buf)
        FfiConverterBoolean.write(value.`signalsRbf`, buf)
    }
}




public object FfiConverterTypeHotTxo: FfiConverterRustBuffer<HotTxo> {
    override fun read(buf: ByteBuffer): HotTxo {
        return HotTxo(
            FfiConverterOptionalString.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterTypeTxoOwner.read(buf),
        )
    }

    override fun allocationSize(value: HotTxo): ULong = (
            FfiConverterOptionalString.allocationSize(value.`address`) +
            FfiConverterULong.allocationSize(value.`valueSats`) +
            FfiConverterTypeTxoOwner.allocationSize(value.`owner`)
    )

    override fun write(value: HotTxo, buf: ByteBuffer) {
        FfiConverterOptionalString.write(value.`address`, buf)
        FfiConverterULong.write(value.`valueSats`, buf)
        FfiConverterTypeTxoOwner.write(value.`owner`, buf)
    }
}




public object FfiConverterTypeHotWalletSnapshot: FfiConverterRustBuffer<HotWalletSnapshot> {
    override fun read(buf: ByteBuffer): HotWalletSnapshot {
        return HotWalletSnapshot(
            FfiConverterString.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterTypeKeyNetwork.read(buf),
            FfiConverterTypeHotScan.read(buf),
        )
    }

    override fun allocationSize(value: HotWalletSnapshot): ULong = (
            FfiConverterString.allocationSize(value.`receiveAddress`) +
            FfiConverterULong.allocationSize(value.`balanceSats`) +
            FfiConverterULong.allocationSize(value.`spendableSats`) +
            FfiConverterTypeKeyNetwork.allocationSize(value.`network`) +
            FfiConverterTypeHotScan.allocationSize(value.`scan`)
    )

    override fun write(value: HotWalletSnapshot, buf: ByteBuffer) {
        FfiConverterString.write(value.`receiveAddress`, buf)
        FfiConverterULong.write(value.`balanceSats`, buf)
        FfiConverterULong.write(value.`spendableSats`, buf)
        FfiConverterTypeKeyNetwork.write(value.`network`, buf)
        FfiConverterTypeHotScan.write(value.`scan`, buf)
    }
}




public object FfiConverterTypeHwAccountKey: FfiConverterRustBuffer<HwAccountKey> {
    override fun read(buf: ByteBuffer): HwAccountKey {
        return HwAccountKey(
            FfiConverterString.read(buf),
            FfiConverterString.read(buf),
            FfiConverterString.read(buf),
        )
    }

    override fun allocationSize(value: HwAccountKey): ULong = (
            FfiConverterString.allocationSize(value.`xpub`) +
            FfiConverterString.allocationSize(value.`masterFingerprint`) +
            FfiConverterString.allocationSize(value.`derivationPath`)
    )

    override fun write(value: HwAccountKey, buf: ByteBuffer) {
        FfiConverterString.write(value.`xpub`, buf)
        FfiConverterString.write(value.`masterFingerprint`, buf)
        FfiConverterString.write(value.`derivationPath`, buf)
    }
}




public object FfiConverterTypePreparedSend: FfiConverterRustBuffer<PreparedSend> {
    override fun read(buf: ByteBuffer): PreparedSend {
        return PreparedSend(
            FfiConverterString.read(buf),
            FfiConverterString.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterULong.read(buf),
        )
    }

    override fun allocationSize(value: PreparedSend): ULong = (
            FfiConverterString.allocationSize(value.`txid`) +
            FfiConverterString.allocationSize(value.`hex`) +
            FfiConverterULong.allocationSize(value.`feeSats`) +
            FfiConverterULong.allocationSize(value.`amountSats`) +
            FfiConverterULong.allocationSize(value.`dustChangeSats`) +
            FfiConverterULong.allocationSize(value.`amountCutSats`)
    )

    override fun write(value: PreparedSend, buf: ByteBuffer) {
        FfiConverterString.write(value.`txid`, buf)
        FfiConverterString.write(value.`hex`, buf)
        FfiConverterULong.write(value.`feeSats`, buf)
        FfiConverterULong.write(value.`amountSats`, buf)
        FfiConverterULong.write(value.`dustChangeSats`, buf)
        FfiConverterULong.write(value.`amountCutSats`, buf)
    }
}




public object FfiConverterTypeSoftwareAccountKey: FfiConverterRustBuffer<SoftwareAccountKey> {
    override fun read(buf: ByteBuffer): SoftwareAccountKey {
        return SoftwareAccountKey(
            FfiConverterSequenceString.read(buf),
            FfiConverterString.read(buf),
            FfiConverterString.read(buf),
            FfiConverterString.read(buf),
        )
    }

    override fun allocationSize(value: SoftwareAccountKey): ULong = (
            FfiConverterSequenceString.allocationSize(value.`mnemonicWords`) +
            FfiConverterString.allocationSize(value.`masterFingerprint`) +
            FfiConverterString.allocationSize(value.`xpub`) +
            FfiConverterString.allocationSize(value.`derivationPath`)
    )

    override fun write(value: SoftwareAccountKey, buf: ByteBuffer) {
        FfiConverterSequenceString.write(value.`mnemonicWords`, buf)
        FfiConverterString.write(value.`masterFingerprint`, buf)
        FfiConverterString.write(value.`xpub`, buf)
        FfiConverterString.write(value.`derivationPath`, buf)
    }
}




public object FfiConverterTypeWalletInfo: FfiConverterRustBuffer<WalletInfo> {
    override fun read(buf: ByteBuffer): WalletInfo {
        return WalletInfo(
            FfiConverterString.read(buf),
            FfiConverterString.read(buf),
            FfiConverterTypeWalletDetails.read(buf),
        )
    }

    override fun allocationSize(value: WalletInfo): ULong = (
            FfiConverterString.allocationSize(value.`name`) +
            FfiConverterString.allocationSize(value.`firmware`) +
            FfiConverterTypeWalletDetails.allocationSize(value.`details`)
    )

    override fun write(value: WalletInfo, buf: ByteBuffer) {
        FfiConverterString.write(value.`name`, buf)
        FfiConverterString.write(value.`firmware`, buf)
        FfiConverterTypeWalletDetails.write(value.`details`, buf)
    }
}





public object FfiConverterTypeAddressType: FfiConverterRustBuffer<AddressType> {
    override fun read(buf: ByteBuffer): AddressType = try {
        AddressType.values()[buf.getInt() - 1]
    } catch (e: IndexOutOfBoundsException) {
        throw RuntimeException("invalid enum value, something is very wrong!!", e)
    }

    override fun allocationSize(value: AddressType): ULong = 4UL

    override fun write(value: AddressType, buf: ByteBuffer) {
        buf.putInt(value.ordinal + 1)
    }
}




public object BleExceptionErrorHandler : UniffiRustCallStatusErrorHandler<BleException> {
    override fun lift(errorBuf: RustBufferByValue): BleException = FfiConverterTypeBleError.lift(errorBuf)
}

public object FfiConverterTypeBleError : FfiConverterRustBuffer<BleException> {
    override fun read(buf: ByteBuffer): BleException {
        return when (buf.getInt()) {
            1 -> BleException.Unsupported(FfiConverterString.read(buf))
            2 -> BleException.PermissionDenied(FfiConverterString.read(buf))
            3 -> BleException.BluetoothOff(FfiConverterString.read(buf))
            4 -> BleException.NotFound(FfiConverterString.read(buf))
            5 -> BleException.WrongDevice(FfiConverterString.read(buf))
            6 -> BleException.PairingCancelled(FfiConverterString.read(buf))
            7 -> BleException.PairingFailed(FfiConverterString.read(buf))
            8 -> BleException.BondRemoved(FfiConverterString.read(buf))
            9 -> BleException.Refused(FfiConverterString.read(buf))
            10 -> BleException.Timeout(FfiConverterString.read(buf))
            11 -> BleException.Disconnected(FfiConverterString.read(buf))
            12 -> BleException.Link(FfiConverterString.read(buf))
            13 -> BleException.Protocol(FfiConverterString.read(buf))
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: BleException): ULong {
        return 4UL
    }

    override fun write(value: BleException, buf: ByteBuffer) {
        when (value) {
            is BleException.Unsupported -> {
                buf.putInt(1)
                Unit
            }
            is BleException.PermissionDenied -> {
                buf.putInt(2)
                Unit
            }
            is BleException.BluetoothOff -> {
                buf.putInt(3)
                Unit
            }
            is BleException.NotFound -> {
                buf.putInt(4)
                Unit
            }
            is BleException.WrongDevice -> {
                buf.putInt(5)
                Unit
            }
            is BleException.PairingCancelled -> {
                buf.putInt(6)
                Unit
            }
            is BleException.PairingFailed -> {
                buf.putInt(7)
                Unit
            }
            is BleException.BondRemoved -> {
                buf.putInt(8)
                Unit
            }
            is BleException.Refused -> {
                buf.putInt(9)
                Unit
            }
            is BleException.Timeout -> {
                buf.putInt(10)
                Unit
            }
            is BleException.Disconnected -> {
                buf.putInt(11)
                Unit
            }
            is BleException.Link -> {
                buf.putInt(12)
                Unit
            }
            is BleException.Protocol -> {
                buf.putInt(13)
                Unit
            }
        }.let { /* this makes the `when` an expression, which ensures it is exhaustive */ }
    }
}





public object FfiConverterTypeHotScan: FfiConverterRustBuffer<HotScan> {
    override fun read(buf: ByteBuffer): HotScan = try {
        HotScan.values()[buf.getInt() - 1]
    } catch (e: IndexOutOfBoundsException) {
        throw RuntimeException("invalid enum value, something is very wrong!!", e)
    }

    override fun allocationSize(value: HotScan): ULong = 4UL

    override fun write(value: HotScan, buf: ByteBuffer) {
        buf.putInt(value.ordinal + 1)
    }
}





public object FfiConverterTypeJadeState: FfiConverterRustBuffer<JadeState> {
    override fun read(buf: ByteBuffer): JadeState = try {
        JadeState.values()[buf.getInt() - 1]
    } catch (e: IndexOutOfBoundsException) {
        throw RuntimeException("invalid enum value, something is very wrong!!", e)
    }

    override fun allocationSize(value: JadeState): ULong = 4UL

    override fun write(value: JadeState, buf: ByteBuffer) {
        buf.putInt(value.ordinal + 1)
    }
}





public object FfiConverterTypeKeyNetwork: FfiConverterRustBuffer<KeyNetwork> {
    override fun read(buf: ByteBuffer): KeyNetwork = try {
        KeyNetwork.values()[buf.getInt() - 1]
    } catch (e: IndexOutOfBoundsException) {
        throw RuntimeException("invalid enum value, something is very wrong!!", e)
    }

    override fun allocationSize(value: KeyNetwork): ULong = 4UL

    override fun write(value: KeyNetwork, buf: ByteBuffer) {
        buf.putInt(value.ordinal + 1)
    }
}





public object FfiConverterTypeMnemonicCheck : FfiConverterRustBuffer<MnemonicCheck>{
    override fun read(buf: ByteBuffer): MnemonicCheck {
        return when(buf.getInt()) {
            1 -> MnemonicCheck.Valid
            2 -> MnemonicCheck.UnknownWord(
                FfiConverterUInt.read(buf),
                )
            3 -> MnemonicCheck.BadWordCount(
                FfiConverterUInt.read(buf),
                )
            4 -> MnemonicCheck.InvalidChecksum
            else -> throw RuntimeException("invalid enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: MnemonicCheck): ULong = when(value) {
        is MnemonicCheck.Valid -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4UL
            )
        }
        is MnemonicCheck.UnknownWord -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4UL
                + FfiConverterUInt.allocationSize(value.`index`)
            )
        }
        is MnemonicCheck.BadWordCount -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4UL
                + FfiConverterUInt.allocationSize(value.`count`)
            )
        }
        is MnemonicCheck.InvalidChecksum -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4UL
            )
        }
    }

    override fun write(value: MnemonicCheck, buf: ByteBuffer) {
        when(value) {
            is MnemonicCheck.Valid -> {
                buf.putInt(1)
                Unit
            }
            is MnemonicCheck.UnknownWord -> {
                buf.putInt(2)
                FfiConverterUInt.write(value.`index`, buf)
                Unit
            }
            is MnemonicCheck.BadWordCount -> {
                buf.putInt(3)
                FfiConverterUInt.write(value.`count`, buf)
                Unit
            }
            is MnemonicCheck.InvalidChecksum -> {
                buf.putInt(4)
                Unit
            }
        }.let { /* this makes the `when` an expression, which ensures it is exhaustive */ }
    }
}




public object MobileSignerExceptionErrorHandler : UniffiRustCallStatusErrorHandler<MobileSignerException> {
    override fun lift(errorBuf: RustBufferByValue): MobileSignerException = FfiConverterTypeMobileSignerError.lift(errorBuf)
}

public object FfiConverterTypeMobileSignerError : FfiConverterRustBuffer<MobileSignerException> {
    override fun read(buf: ByteBuffer): MobileSignerException {
        return when (buf.getInt()) {
            1 -> MobileSignerException.InvalidPsbtJson(FfiConverterString.read(buf))
            2 -> MobileSignerException.InvalidXpub(FfiConverterString.read(buf))
            3 -> MobileSignerException.NetworkMismatch(FfiConverterString.read(buf))
            4 -> MobileSignerException.DuplicateXpub(FfiConverterString.read(buf))
            5 -> MobileSignerException.InvalidThreshold(FfiConverterString.read(buf))
            6 -> MobileSignerException.VaultCore(FfiConverterString.read(buf))
            7 -> MobileSignerException.InvalidMnemonic(FfiConverterString.read(buf))
            8 -> MobileSignerException.Network(FfiConverterString.read(buf))
            9 -> MobileSignerException.BroadcastUnknown(FfiConverterString.read(buf))
            else -> throw RuntimeException("invalid error enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: MobileSignerException): ULong {
        return 4UL
    }

    override fun write(value: MobileSignerException, buf: ByteBuffer) {
        when (value) {
            is MobileSignerException.InvalidPsbtJson -> {
                buf.putInt(1)
                Unit
            }
            is MobileSignerException.InvalidXpub -> {
                buf.putInt(2)
                Unit
            }
            is MobileSignerException.NetworkMismatch -> {
                buf.putInt(3)
                Unit
            }
            is MobileSignerException.DuplicateXpub -> {
                buf.putInt(4)
                Unit
            }
            is MobileSignerException.InvalidThreshold -> {
                buf.putInt(5)
                Unit
            }
            is MobileSignerException.VaultCore -> {
                buf.putInt(6)
                Unit
            }
            is MobileSignerException.InvalidMnemonic -> {
                buf.putInt(7)
                Unit
            }
            is MobileSignerException.Network -> {
                buf.putInt(8)
                Unit
            }
            is MobileSignerException.BroadcastUnknown -> {
                buf.putInt(9)
                Unit
            }
        }.let { /* this makes the `when` an expression, which ensures it is exhaustive */ }
    }
}





public object FfiConverterTypeTxoOwner: FfiConverterRustBuffer<TxoOwner> {
    override fun read(buf: ByteBuffer): TxoOwner = try {
        TxoOwner.values()[buf.getInt() - 1]
    } catch (e: IndexOutOfBoundsException) {
        throw RuntimeException("invalid enum value, something is very wrong!!", e)
    }

    override fun allocationSize(value: TxoOwner): ULong = 4UL

    override fun write(value: TxoOwner, buf: ByteBuffer) {
        buf.putInt(value.ordinal + 1)
    }
}





public object FfiConverterTypeWalletDetails : FfiConverterRustBuffer<WalletDetails>{
    override fun read(buf: ByteBuffer): WalletDetails {
        return when(buf.getInt()) {
            1 -> WalletDetails.Jade(
                FfiConverterString.read(buf),
                FfiConverterTypeJadeState.read(buf),
                FfiConverterString.read(buf),
                )
            2 -> WalletDetails.TrezorSafe7(
                FfiConverterString.read(buf),
                FfiConverterOptionalString.read(buf),
                FfiConverterOptionalString.read(buf),
                FfiConverterUShort.read(buf),
                )
            else -> throw RuntimeException("invalid enum value, something is very wrong!!")
        }
    }

    override fun allocationSize(value: WalletDetails): ULong = when(value) {
        is WalletDetails.Jade -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4UL
                + FfiConverterString.allocationSize(value.`board`)
                + FfiConverterTypeJadeState.allocationSize(value.`state`)
                + FfiConverterString.allocationSize(value.`networks`)
            )
        }
        is WalletDetails.TrezorSafe7 -> {
            // Add the size for the Int that specifies the variant plus the size needed for all fields
            (
                4UL
                + FfiConverterString.allocationSize(value.`internalModel`)
                + FfiConverterOptionalString.allocationSize(value.`deviceId`)
                + FfiConverterOptionalString.allocationSize(value.`label`)
                + FfiConverterUShort.allocationSize(value.`mtu`)
            )
        }
    }

    override fun write(value: WalletDetails, buf: ByteBuffer) {
        when(value) {
            is WalletDetails.Jade -> {
                buf.putInt(1)
                FfiConverterString.write(value.`board`, buf)
                FfiConverterTypeJadeState.write(value.`state`, buf)
                FfiConverterString.write(value.`networks`, buf)
                Unit
            }
            is WalletDetails.TrezorSafe7 -> {
                buf.putInt(2)
                FfiConverterString.write(value.`internalModel`, buf)
                FfiConverterOptionalString.write(value.`deviceId`, buf)
                FfiConverterOptionalString.write(value.`label`, buf)
                FfiConverterUShort.write(value.`mtu`, buf)
                Unit
            }
        }.let { /* this makes the `when` an expression, which ensures it is exhaustive */ }
    }
}





public object FfiConverterTypeWalletKind: FfiConverterRustBuffer<WalletKind> {
    override fun read(buf: ByteBuffer): WalletKind = try {
        WalletKind.values()[buf.getInt() - 1]
    } catch (e: IndexOutOfBoundsException) {
        throw RuntimeException("invalid enum value, something is very wrong!!", e)
    }

    override fun allocationSize(value: WalletKind): ULong = 4UL

    override fun write(value: WalletKind, buf: ByteBuffer) {
        buf.putInt(value.ordinal + 1)
    }
}




public object FfiConverterOptionalULong: FfiConverterRustBuffer<kotlin.ULong?> {
    override fun read(buf: ByteBuffer): kotlin.ULong? {
        if (buf.get().toInt() == 0) {
            return null
        }
        return FfiConverterULong.read(buf)
    }

    override fun allocationSize(value: kotlin.ULong?): ULong {
        if (value == null) {
            return 1UL
        } else {
            return 1UL + FfiConverterULong.allocationSize(value)
        }
    }

    override fun write(value: kotlin.ULong?, buf: ByteBuffer) {
        if (value == null) {
            buf.put(0)
        } else {
            buf.put(1)
            FfiConverterULong.write(value, buf)
        }
    }
}




public object FfiConverterOptionalString: FfiConverterRustBuffer<kotlin.String?> {
    override fun read(buf: ByteBuffer): kotlin.String? {
        if (buf.get().toInt() == 0) {
            return null
        }
        return FfiConverterString.read(buf)
    }

    override fun allocationSize(value: kotlin.String?): ULong {
        if (value == null) {
            return 1UL
        } else {
            return 1UL + FfiConverterString.allocationSize(value)
        }
    }

    override fun write(value: kotlin.String?, buf: ByteBuffer) {
        if (value == null) {
            buf.put(0)
        } else {
            buf.put(1)
            FfiConverterString.write(value, buf)
        }
    }
}




public object FfiConverterOptionalTypeHotTxDetail: FfiConverterRustBuffer<HotTxDetail?> {
    override fun read(buf: ByteBuffer): HotTxDetail? {
        if (buf.get().toInt() == 0) {
            return null
        }
        return FfiConverterTypeHotTxDetail.read(buf)
    }

    override fun allocationSize(value: HotTxDetail?): ULong {
        if (value == null) {
            return 1UL
        } else {
            return 1UL + FfiConverterTypeHotTxDetail.allocationSize(value)
        }
    }

    override fun write(value: HotTxDetail?, buf: ByteBuffer) {
        if (value == null) {
            buf.put(0)
        } else {
            buf.put(1)
            FfiConverterTypeHotTxDetail.write(value, buf)
        }
    }
}




public object FfiConverterSequenceString: FfiConverterRustBuffer<List<kotlin.String>> {
    override fun read(buf: ByteBuffer): List<kotlin.String> {
        val len = buf.getInt()
        return List<kotlin.String>(len) {
            FfiConverterString.read(buf)
        }
    }

    override fun allocationSize(value: List<kotlin.String>): ULong {
        val sizeForLength = 4UL
        val sizeForItems = value.sumOf { FfiConverterString.allocationSize(it) }
        return sizeForLength + sizeForItems
    }

    override fun write(value: List<kotlin.String>, buf: ByteBuffer) {
        buf.putInt(value.size)
        value.iterator().forEach {
            FfiConverterString.write(it, buf)
        }
    }
}




public object FfiConverterSequenceTypeFoundWallet: FfiConverterRustBuffer<List<FoundWallet>> {
    override fun read(buf: ByteBuffer): List<FoundWallet> {
        val len = buf.getInt()
        return List<FoundWallet>(len) {
            FfiConverterTypeFoundWallet.read(buf)
        }
    }

    override fun allocationSize(value: List<FoundWallet>): ULong {
        val sizeForLength = 4UL
        val sizeForItems = value.sumOf { FfiConverterTypeFoundWallet.allocationSize(it) }
        return sizeForLength + sizeForItems
    }

    override fun write(value: List<FoundWallet>, buf: ByteBuffer) {
        buf.putInt(value.size)
        value.iterator().forEach {
            FfiConverterTypeFoundWallet.write(it, buf)
        }
    }
}




public object FfiConverterSequenceTypeHotTx: FfiConverterRustBuffer<List<HotTx>> {
    override fun read(buf: ByteBuffer): List<HotTx> {
        val len = buf.getInt()
        return List<HotTx>(len) {
            FfiConverterTypeHotTx.read(buf)
        }
    }

    override fun allocationSize(value: List<HotTx>): ULong {
        val sizeForLength = 4UL
        val sizeForItems = value.sumOf { FfiConverterTypeHotTx.allocationSize(it) }
        return sizeForLength + sizeForItems
    }

    override fun write(value: List<HotTx>, buf: ByteBuffer) {
        buf.putInt(value.size)
        value.iterator().forEach {
            FfiConverterTypeHotTx.write(it, buf)
        }
    }
}




public object FfiConverterSequenceTypeHotTxo: FfiConverterRustBuffer<List<HotTxo>> {
    override fun read(buf: ByteBuffer): List<HotTxo> {
        val len = buf.getInt()
        return List<HotTxo>(len) {
            FfiConverterTypeHotTxo.read(buf)
        }
    }

    override fun allocationSize(value: List<HotTxo>): ULong {
        val sizeForLength = 4UL
        val sizeForItems = value.sumOf { FfiConverterTypeHotTxo.allocationSize(it) }
        return sizeForLength + sizeForItems
    }

    override fun write(value: List<HotTxo>, buf: ByteBuffer) {
        buf.putInt(value.size)
        value.iterator().forEach {
            FfiConverterTypeHotTxo.write(it, buf)
        }
    }
}













/**
 * The words that turn `first_words` into a valid mnemonic, in list order. For 23
 * words that is 8: the last word carries 3 entropy bits and 8 checksum bits.
 * Empty when a word is not in the list or the count cannot become 12, 15, 18, 21
 * or 24 words.
 */
public actual fun `bip39LastWordCandidates`(`firstWords`: List<kotlin.String>): List<kotlin.String> {
    return FfiConverterSequenceString.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_bip39_last_word_candidates(
            FfiConverterSequenceString.lower(`firstWords`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Up to `limit` BIP-39 English words that start with `prefix`, in list order.
 * An empty prefix gives none.
 */
public actual fun `bip39Suggest`(`prefix`: kotlin.String, `limit`: kotlin.UInt): List<kotlin.String> {
    return FfiConverterSequenceString.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_bip39_suggest(
            FfiConverterString.lower(`prefix`),
            FfiConverterUInt.lower(`limit`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Is `words` a valid BIP-39 English mnemonic, and if not, why? Nothing is retained.
 */
@Throws(MobileSignerException::class)
public actual fun `checkMnemonic`(`words`: List<kotlin.String>): MnemonicCheck {
    return FfiConverterTypeMnemonicCheck.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_check_mnemonic(
            FfiConverterSequenceString.lower(`words`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * MOCK, legacy three-party (borrower/platform/lender) 2-of-3 lending vault.
 * Returns `"tb1q"` + hex of a Rust `DefaultHasher` digest: not a Bitcoin
 * address, and not stable across Rust releases. For a real address use
 * `compute_vault_address_multisig`.
 */
public actual fun `computeVaultAddress`(`borrowerPubkey`: kotlin.String, `platformPubkey`: kotlin.String, `lenderPubkey`: kotlin.String): kotlin.String {
    return FfiConverterString.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_compute_vault_address(
            FfiConverterString.lower(`borrowerPubkey`),
            FfiConverterString.lower(`platformPubkey`),
            FfiConverterString.lower(`lenderPubkey`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * First receive address of a `threshold`-of-`xpubs.len()` P2WSH multisig: address
 * 0 of vault-core's sorted-multisig descriptor over `<xpub>/0/<index>` for every key.
 *
 * - `xpubs`: base58 `xpub` (mainnet) or `tpub` (test networks), any order
 * (sortedmulti), all from one network, no public key twice.
 * - Network is inferred from the version bytes, because the fixed two-argument
 * signature has no network parameter: `xpub` gives `bc1...`; `tpub` gives
 * `tb1...` (built as `Network::Testnet4`; the string is identical for testnet3
 * and signet). Regtest (`bcrt1...`) cannot be produced by this function.
 * - Index is fixed at 0 for the same reason. Other indexes and the change
 * chain (derivation path `/1/<index>`) need a new export, not a new argument here.
 * - `threshold`: 1 <= threshold <= xpubs.len() <= 20.
 */
@Throws(MobileSignerException::class)
public actual fun `computeVaultAddressMultisig`(`xpubs`: List<kotlin.String>, `threshold`: kotlin.UByte): kotlin.String {
    return FfiConverterString.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_compute_vault_address_multisig(
            FfiConverterSequenceString.lower(`xpubs`),
            FfiConverterUByte.lower(`threshold`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * MOCK. vault-core's `derive_child_pubkey` is a format string: this returns
 * `"{account_xpub}/m/48'/0'/0'/2'/{index}"`, not a public key.
 */
public actual fun `deriveBorrowerPubkey`(`accountXpub`: kotlin.String, `index`: kotlin.UInt): kotlin.String {
    return FfiConverterString.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_derive_borrower_pubkey(
            FfiConverterString.lower(`accountXpub`),
            FfiConverterUInt.lower(`index`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Baht per bitcoin from Bitkub, or from CoinGecko when Bitkub fails or answers nonsense.
 * Neither publishes terms for use inside an app: fine for this testnet build, not for production.
 *
 * Blocks for up to 20 seconds when both sources hang, so call it off the main thread. Fails with
 * `Network`, which names both reasons, when neither source gives a price.
 */
@Throws(MobileSignerException::class)
public actual fun `fetchBtcThb`(): BtcPrice {
    return FfiConverterTypeBtcPrice.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_fetch_btc_thb(
            uniffiRustCallStatus,
        )
    })
}

/**
 * A new 24-word BIP-39 mnemonic and its BIP-48 P2WSH account key.
 *
 * `passphrase` may be empty. `Main` derives an `xpub` at `m/48'/0'/account'/2'`.
 * `Test` derives a `tpub` at `m/48'/1'/account'/2'` on testnet4.
 * The words are returned once, in `mnemonic_words`.
 */
@Throws(MobileSignerException::class)
public actual fun `generateSoftwareMultisigKey`(`passphrase`: kotlin.String, `network`: KeyNetwork, `account`: kotlin.UInt): SoftwareAccountKey {
    return FfiConverterTypeSoftwareAccountKey.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_generate_software_multisig_key(
            FfiConverterString.lower(`passphrase`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterUInt.lower(`account`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Post a transaction from `hot_prepare_send_at_rate` once. `txid` must be the id of the transaction in
 * `hex`, else it refuses before posting, so nothing else can go out under a saved guard.
 *
 * When the explorer's answer leaves it unclear whether the payment went out, it looks `txid` up a
 * few times and fails with `BroadcastUnknown` if it is still not found. The payment may be out
 * then, so the app must not offer it as a failure to retry. A silent explorer can hold this call
 * for about two minutes, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public actual fun `hotBroadcast`(`network`: KeyNetwork, `hex`: kotlin.String, `txid`: kotlin.String): HotSendResult {
    return FfiConverterTypeHotSendResult.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_broadcast(
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterString.lower(`hex`),
            FfiConverterString.lower(`txid`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * The fee tiers for the Send screen, from the testnet4 host in use. One network call in the usual
 * case, answered from memory for five minutes after. Never fails: when no host answers or the
 * answer is unusable the fixed tiers 4, 8 and 15 come back with `live` false.
 */
public actual fun `hotFeeTiers`(`network`: KeyNetwork): FeeTiers {
    return FfiConverterTypeFeeTiers.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_fee_tiers(
            FfiConverterTypeKeyNetwork.lower(`network`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Forgets every wallet's scan, in memory and in the folder `hot_use_state_dir` named. Call it when the
 * wallet is erased.
 */
public actual fun `hotForgetScanState`() {
    uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_forget_scan_state(
            uniffiRustCallStatus,
        )
    }
}

/**
 * Scan, then sign a cancel of `earlier_hex`, the signed payment the app's guard keeps, and post
 * nothing. It spends exactly that payment's inputs in its order and pays them back to this wallet in
 * one output, to its change address when it had change and else to the next unused one. The fee follows the rules of
 * `hot_prepare_fee_bump` for that one output, and `amount_sats` is what comes back. Until a block holds
 * one of the two, the earlier payment may still confirm instead. The same refusals as
 * `hot_prepare_fee_bump`, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public actual fun `hotPrepareCancel`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `earlierHex`: kotlin.String, `feeRateSatVb`: kotlin.ULong): PreparedSend {
    return FfiConverterTypePreparedSend.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_prepare_cancel(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterString.lower(`passphrase`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterTypeAddressType.lower(`addressType`),
            FfiConverterString.lower(`earlierHex`),
            FfiConverterULong.lower(`feeRateSatVb`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Scan, then sign a fee bump of `earlier_hex`, the signed payment the app's guard keeps, and post
 * nothing. It spends every input of that payment in its order, so at most one of the two can ever
 * confirm, and its first output stays as it was. More confirmed coins join only when the earlier
 * inputs cannot pay the new fee, change goes to the same change address, or for a payment with no
 * change to the next unused one, as Bitcoin Core's bumpfee does, and change below its dust floor goes
 * into the fee. Where that would pass 10,000 sat/vB on the bump's size, the change stays at exactly its
 * floor and the fee is the rest, under the rate asked but within rules 3, 4 and 6, so the amount is
 * kept. Only a payment with no change (a send all, or one whose change went into its fee)
 * whose amount no confirmed coin can keep gets one output worth its own inputs less the new fee, and
 * `amount_cut_sats` says how much less the destination gets. The fee meets BIP-125 rules 3, 4 and 6
 * against that payment. It is at
 * least that payment's fee plus the new size at 1 sat/vB, at a rate strictly above its rate, so the
 * rate can be above `fee_rate_sat_vb`. Refuses, before any lookup, bytes that are not one transaction with inputs and one
 * or two outputs whose first is at least its dust floor, and a rate outside 1 to 10,000 sat/vB. After the scan
 * it refuses, after `replace: `, a second output that is not this wallet's change, inputs that are not
 * all coins this wallet can spend now (`coins spent:`, most likely because that payment is out), an
 * input without this wallet's own SIGHASH_ALL signature for its coin, a rate that would pass 10,000
 * sat/vB, on the rate asked or on the signed bytes, what is left below the dust floor of its
 * output, and a bump above the network's 100,000 vB (`too many coins:`), and without `replace: ` a
 * payment with change whose amount the confirmed coins cannot keep
 * (`insufficient funds:`). The same scan as `hot_prepare_send_at_rate`, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public actual fun `hotPrepareFeeBump`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `earlierHex`: kotlin.String, `feeRateSatVb`: kotlin.ULong): PreparedSend {
    return FfiConverterTypePreparedSend.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_prepare_fee_bump(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterString.lower(`passphrase`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterTypeAddressType.lower(`addressType`),
            FfiConverterString.lower(`earlierHex`),
            FfiConverterULong.lower(`feeRateSatVb`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Scan, spend every coin `hot_prepare_send_at_rate` would consider, and sign one output paying
 * `destination` the whole balance less the fee, with no change, and post nothing. The fee is the
 * size of that transaction times `fee_rate_sat_vb`, so it and the amount are only known once the
 * coins are: the answer's `amount_sats` is what leaves. Refuses, before signing, a rate outside
 * 1 to 10,000 sat/vB, a wallet with nothing confirmed to spend, coins that need more than the
 * network's 100,000 vB in one transaction (`too many coins:`), and a balance that would leave
 * less than the destination's dust floor after the fee, and after signing what
 * `hot_prepare_send_at_rate` refuses past the network's limit. The same scan as
 * `hot_prepare_send_at_rate`, so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public actual fun `hotPrepareSendAll`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `destination`: kotlin.String, `feeRateSatVb`: kotlin.ULong): PreparedSend {
    return FfiConverterTypePreparedSend.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_prepare_send_all(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterString.lower(`passphrase`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterTypeAddressType.lower(`addressType`),
            FfiConverterString.lower(`destination`),
            FfiConverterULong.lower(`feeRateSatVb`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Scan, select coins, check and sign a payment at `fee_rate_sat_vb`, and post nothing. The only
 * network use is the Esplora lookups of the scan. The app saves the `txid` as its payment guard
 * before it calls `hot_broadcast`, so a kill between the two can never lead to a second payment.
 *
 * Coins are added largest first, one at a time, until they pay the amount and the fee of the
 * transaction they make, so any payment some of the coins can make is signed. The fee is the size
 * of the transaction as built times the rate, so a payment that needs more coins pays more, never a
 * fixed sum that falls under the rate. The answer's `fee_sats` is that fee, and the app compares it
 * with the estimate its screens showed and asks the user when it is much higher. Refuses, before
 * signing, a rate outside 1 to 10,000 sat/vB (the network's limit), an amount below the dust floor
 * of the destination's script (294 sats for P2WPKH up to 546 for P2PKH, the outputs the network
 * refuses), an amount that overflows u64 with its fee, coins that cannot pay it (`insufficient
 * funds:`) and a payment that needs more than the network's 100,000 vB (`too many coins:`), and
 * after signing a fee above 10,000 sat/vB times the signed transaction's virtual size. Change below
 * the change address's dust floor would be refused by the network too, and change too small to pay
 * for its own output costs more than it returns, so either goes into the fee and the answer's
 * `dust_change_sats` names it. Where that would pass 10,000 sat/vB, the change stays at exactly its
 * floor and the fee is the rest, under the rate asked. A fee above the amount is signed, because
 * the fee is paid on top and the destination still gets the amount, so the app asks the user before
 * it posts.
 * Confirmed coins pay first, and the wallet's own unconfirmed change only when they fall short. A
 * coin someone else sent is spent only once confirmed. About 40 Esplora calls for a fresh wallet,
 * so call it off the main thread.
 */
@Throws(MobileSignerException::class)
public actual fun `hotPrepareSendAtRate`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `destination`: kotlin.String, `amountSats`: kotlin.ULong, `feeRateSatVb`: kotlin.ULong): PreparedSend {
    return FfiConverterTypePreparedSend.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_prepare_send_at_rate(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterString.lower(`passphrase`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterTypeAddressType.lower(`addressType`),
            FfiConverterString.lower(`destination`),
            FfiConverterULong.lower(`amountSats`),
            FfiConverterULong.lower(`feeRateSatVb`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Receive address `index` (usually 0) of `address_type`'s account. `passphrase` is the
 * BIP-39 passphrase the key was restored with, empty for none: a different
 * passphrase is a different wallet. Mainnet takes Native SegWit only.
 */
@Throws(MobileSignerException::class)
public actual fun `hotReceiveAddress`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `index`: kotlin.UInt): kotlin.String {
    return FfiConverterString.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_receive_address(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterString.lower(`passphrase`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterTypeAddressType.lower(`addressType`),
            FfiConverterUInt.lower(`index`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * One transaction of the wallet in full, or None when the explorer does not know `txid`.
 */
@Throws(MobileSignerException::class)
public actual fun `hotTxDetail`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType, `txid`: kotlin.String): HotTxDetail? {
    return FfiConverterOptionalTypeHotTxDetail.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_tx_detail(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterString.lower(`passphrase`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterTypeAddressType.lower(`addressType`),
            FfiConverterString.lower(`txid`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Keeps each wallet's scan in `dir`, app storage that no backup copies, so the first sync after a restart
 * asks only what can have changed. Call it once at app start. Without it scans live in memory only.
 */
public actual fun `hotUseStateDir`(`dir`: kotlin.String) {
    uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_use_state_dir(
            FfiConverterString.lower(`dir`),
            uniffiRustCallStatus,
        )
    }
}

/**
 * The latest transactions of the wallet's address, each as the wallet saw it. The order is
 * Esplora's: the mempool first, then confirmed transactions newest first. Only the first page
 * comes back (about 25 confirmed transactions plus the mempool), so older history is not here.
 *
 * Makes two network calls, so call it off the main thread. An answer it cannot read fails the
 * whole call, never one transaction.
 */
@Throws(MobileSignerException::class)
public actual fun `hotWalletHistory`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType): List<HotTx> {
    return FfiConverterSequenceTypeHotTx.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_wallet_history(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterString.lower(`passphrase`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterTypeAddressType.lower(`addressType`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Confirmed + mempool balance for receive index 0 via Esplora.
 */
@Throws(MobileSignerException::class)
public actual fun `hotWalletSnapshot`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `addressType`: AddressType): HotWalletSnapshot {
    return FfiConverterTypeHotWalletSnapshot.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_wallet_snapshot(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterString.lower(`passphrase`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterTypeAddressType.lower(`addressType`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * True when `address` parses and belongs to `network`: the same test `hot_prepare_send_at_rate` applies to its destination.
 */
public actual fun `isValidAddress`(`address`: kotlin.String, `network`: KeyNetwork): kotlin.Boolean {
    return FfiConverterBoolean.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_is_valid_address(
            FfiConverterString.lower(`address`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * How many `WalletLink` objects exist in this process. For tests, not a stable API: a link
 * that a call kept alive after `close()` still counts here.
 */
public actual fun `liveWalletLinks`(): kotlin.ULong {
    return FfiConverterULong.lift(uniffiRustCall { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_live_wallet_links(
            uniffiRustCallStatus,
        )
    })
}

/**
 * The BIP-48 P2WSH account key for an existing BIP-39 mnemonic.
 *
 * `mnemonic_words` is the 12, 15, 18, 21 or 24 words, or one string that
 * already joins them with spaces. The record echoes the normalized words.
 * `passphrase`, `network` and `account` mean the same as in
 * `generate_software_multisig_key`.
 */
@Throws(MobileSignerException::class)
public actual fun `restoreSoftwareMultisigKey`(`mnemonicWords`: List<kotlin.String>, `passphrase`: kotlin.String, `network`: KeyNetwork, `account`: kotlin.UInt): SoftwareAccountKey {
    return FfiConverterTypeSoftwareAccountKey.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_restore_software_multisig_key(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterString.lower(`passphrase`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterUInt.lower(`account`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Scans for a few seconds and lists the wallets of `kind` in range.
 * Cancelling stops the scan.
 */
@Throws(BleException::class, kotlin.coroutines.cancellation.CancellationException::class)
public actual suspend fun `scanWallets`(`kind`: WalletKind): List<FoundWallet> {
    return uniffiRustCallAsync(
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_scan_wallets(
            FfiConverterTypeWalletKind.lower(`kind`),
        ),
        { future, callback, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_poll_rust_buffer(future, callback, continuation) },
        { future, continuation -> UniffiLib.ffi_mobile_signer_ffi_rust_future_complete_rust_buffer(future, continuation) },
        { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_free_rust_buffer(future) },
        { future -> UniffiLib.ffi_mobile_signer_ffi_rust_future_cancel_rust_buffer(future) },
        // lift function
        { FfiConverterSequenceTypeFoundWallet.lift(it) },
        // Error FFI converter
        BleExceptionErrorHandler,
    )
}

/**
 * MOCK. Returns `"MOCK_SIG[{loan_id}:{output count}:{borrower_pubkey}]"`. Signs
 * nothing and runs no policy check (`PolicyEngine::authorize` is not called).
 */
@Throws(MobileSignerException::class)
public actual fun `signPsbt`(`psbtJson`: kotlin.String, `borrowerPubkey`: kotlin.String): kotlin.String {
    return FfiConverterString.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_sign_psbt(
            FfiConverterString.lower(`psbtJson`),
            FfiConverterString.lower(`borrowerPubkey`),
            uniffiRustCallStatus,
        )
    })
}


// Async support

internal const val UNIFFI_RUST_FUTURE_POLL_READY = 0.toByte()
internal const val UNIFFI_RUST_FUTURE_POLL_MAYBE_READY = 1.toByte()

internal val uniffiContinuationHandleMap = UniffiHandleMap<CancellableContinuation<Byte>>()

// FFI type for Rust future continuations
internal suspend fun<T, F, E: kotlin.Exception> uniffiRustCallAsync(
    rustFuture: Long,
    pollFunc: (Long, UniffiRustFutureContinuationCallback, Long) -> Unit,
    completeFunc: (Long, UniffiRustCallStatus) -> F,
    freeFunc: (Long) -> Unit,
    cancelFunc: (Long) -> Unit,
    liftFunc: (F) -> T,
    errorHandler: UniffiRustCallStatusErrorHandler<E>
): T {
    // Patched by scripts/patch_bindings.py. Gobley freed the future in a finally inside withContext.
    // withContext throws without running its block when the caller is cancelled first (already, or
    // while the block waits for Dispatchers.IO), so that finally never ran and the future, with the
    // object it holds, leaked. withContext returns or throws only after its block has ended or was
    // never started, so freeing here runs exactly once on every path.
    try {
        return withContext(Dispatchers.IO) {
            do {
                val pollResult = suspendCancellableCoroutine<Byte> { continuation ->
                    val handle = uniffiContinuationHandleMap.insert(continuation)
                    continuation.invokeOnCancellation {
                        cancelFunc(rustFuture)
                    }
                    pollFunc(
                        rustFuture,
                        uniffiRustFutureContinuationCallbackCallback,
                        handle
                    )
                }
            } while (pollResult != UNIFFI_RUST_FUTURE_POLL_READY);

            return@withContext liftFunc(
                uniffiRustCallWithError(errorHandler) { status -> completeFunc(rustFuture, status) }
            )
        }
    } finally {
        freeFunc(rustFuture)
    }
}
internal inline fun<T> uniffiTraitInterfaceCallAsync(
    crossinline makeCall: suspend () -> T,
    crossinline handleSuccess: (T) -> Unit,
    crossinline handleError: (UniffiRustCallStatusByValue) -> Unit,
): UniffiForeignFutureUniffiByValue {
    // Using `GlobalScope` is labeled as a "delicate API" and generally discouraged in Kotlin programs, since it breaks structured concurrency.
    // However, our parent task is a Rust future, so we're going to need to break structure concurrency in any case.
    //
    // Uniffi does its best to support structured concurrency across the FFI.
    // If the Rust future is dropped, `uniffiForeignFutureFreeImpl` is called, which will cancel the Kotlin coroutine if it's still running.
    @OptIn(DelicateCoroutinesApi::class)
    val job = GlobalScope.launch {
        try {
            handleSuccess(makeCall())
        } catch(e: kotlin.Exception) {
            val status = UniffiRustCallStatusByValue(
                UNIFFI_CALL_UNEXPECTED_ERROR,
                FfiConverterString.lower(e.toString()),
            )
            handleError(status)
        }
    }
    val handle = uniffiForeignFutureHandleMap.insert(job)
    return UniffiForeignFutureUniffiByValue(handle, uniffiForeignFutureFreeImpl)
}

internal inline fun<T, reified E: Throwable> uniffiTraitInterfaceCallAsyncWithError(
    crossinline makeCall: suspend () -> T,
    crossinline handleSuccess: (T) -> Unit,
    crossinline handleError: (UniffiRustCallStatusByValue) -> Unit,
    crossinline lowerError: (E) -> RustBufferByValue,
): UniffiForeignFutureUniffiByValue {
    // See uniffiTraitInterfaceCallAsync for details on `DelicateCoroutinesApi`
    @OptIn(DelicateCoroutinesApi::class)
    val job = GlobalScope.launch {
        try {
            handleSuccess(makeCall())
        } catch(e: kotlin.Exception) {
            if (e is E) {
                val status = UniffiRustCallStatusByValue(
                    UNIFFI_CALL_ERROR,
                    lowerError(e),
                )
                handleError(status)
            } else {
                val status = UniffiRustCallStatusByValue(
                    UNIFFI_CALL_UNEXPECTED_ERROR,
                    FfiConverterString.lower(e.toString()),
                )
                handleError(status)
            }
        }
    }
    val handle = uniffiForeignFutureHandleMap.insert(job)
    return UniffiForeignFutureUniffiByValue(handle, uniffiForeignFutureFreeImpl)
}

internal val uniffiForeignFutureHandleMap = UniffiHandleMap<Job>()

internal object uniffiRustFutureContinuationCallbackCallback: UniffiRustFutureContinuationCallback {
    override fun callback(data: Long, pollResult: Byte) {
        uniffiContinuationHandleMap.remove(data).resume(pollResult)
    }
}

internal object uniffiForeignFutureFreeImpl: UniffiForeignFutureFree {
    override fun callback(handle: Long) {
        val job = uniffiForeignFutureHandleMap.remove(handle)
        if (!job.isCompleted) {
            job.cancel()
        }
    }
}