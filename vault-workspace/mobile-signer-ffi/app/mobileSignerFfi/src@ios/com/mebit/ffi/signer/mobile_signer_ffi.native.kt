

@file:Suppress("RemoveRedundantBackticks")
@file:OptIn(ExperimentalForeignApi::class)

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

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.ShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.useContents
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.usePinned
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.value
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.write
import kotlin.coroutines.resume
import platform.posix.memcpy
import kotlin.coroutines.resume
import kotlin.native.ref.createCleaner
import kotlinx.atomicfu.AtomicBoolean
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.invoke
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext


internal typealias Pointer = CPointer<out kotlinx.cinterop.CPointed>
internal val NullPointer: Pointer? = null
internal fun Pointer.toLong(): Long = rawValue.toLong()
internal fun kotlin.Long.toPointer(): Pointer = requireNotNull(this.toCPointer())


public class ByteBuffer(
    internal val pointer: CPointer<kotlinx.cinterop.ByteVar>,
    internal val capacity: Int,
    internal var position: Int = 0,
) {
    public fun position(): Int = position

    public fun hasRemaining(): Boolean = capacity != position

    private fun checkRemaining(bytes: Int) {
        val remaining = capacity - position
        require(bytes <= remaining) { 
            "buffer is exhausted: required: $bytes, remaining: $remaining, capacity: $capacity, position: $position" 
        }
    }

    public fun get(): Byte {
        checkRemaining(1)
        return pointer[position++]
    }

    public fun get(bytesToRead: Int): ByteArray {
        checkRemaining(bytesToRead)
        val result = ByteArray(bytesToRead)
        if (result.isNotEmpty()) {
            result.usePinned { pinned ->
                memcpy(pinned.addressOf(0), pointer + position, bytesToRead.convert())
            }
            position += bytesToRead
        }
        return result
    }

    public fun getShort(): Short {
        checkRemaining(2)
        return (((pointer[position++].toInt() and 0xff) shl 8)
                or (pointer[position++].toInt() and 0xff)).toShort()
    }

    public fun getInt(): Int {
        checkRemaining(4)
        return (((pointer[position++].toInt() and 0xff) shl 24)
                or ((pointer[position++].toInt() and 0xff) shl 16)
                or ((pointer[position++].toInt() and 0xff) shl 8)
                or (pointer[position++].toInt() and 0xff))
    }

    public fun getLong(): Long {
        checkRemaining(8)
        return (((pointer[position++].toLong() and 0xffL) shl 56)
                or ((pointer[position++].toLong() and 0xffL) shl 48)
                or ((pointer[position++].toLong() and 0xffL) shl 40)
                or ((pointer[position++].toLong() and 0xffL) shl 32)
                or ((pointer[position++].toLong() and 0xffL) shl 24)
                or ((pointer[position++].toLong() and 0xffL) shl 16)
                or ((pointer[position++].toLong() and 0xffL) shl 8)
                or (pointer[position++].toLong() and 0xffL))
    }

    public fun getFloat(): Float = Float.fromBits(getInt())

    public fun getDouble(): Double = Double.fromBits(getLong())

    public fun put(value: Byte) {
        checkRemaining(1)
        pointer[position++] = value
    }

    public fun put(src: ByteArray) {
        checkRemaining(src.size)
        if (src.isNotEmpty()) {
            src.usePinned { pinned ->
                memcpy(pointer + position, pinned.addressOf(0), src.size.convert())
            }
            position += src.size
        }
    }

    public fun putShort(value: Short) {
        checkRemaining(2)
        pointer[position++] = (value.toInt() ushr 8 and 0xff).toByte()
        pointer[position++] = (value.toInt() and 0xff).toByte()
    }

    public fun putInt(value: Int) {
        checkRemaining(4)
        pointer[position++] = (value ushr 24 and 0xff).toByte()
        pointer[position++] = (value ushr 16 and 0xff).toByte()
        pointer[position++] = (value ushr 8 and 0xff).toByte()
        pointer[position++] = (value and 0xff).toByte()
    }

    public fun putLong(value: Long) {
        checkRemaining(8)
        pointer[position++] = (value ushr 56 and 0xffL).toByte()
        pointer[position++] = (value ushr 48 and 0xffL).toByte()
        pointer[position++] = (value ushr 40 and 0xffL).toByte()
        pointer[position++] = (value ushr 32 and 0xffL).toByte()
        pointer[position++] = (value ushr 24 and 0xffL).toByte()
        pointer[position++] = (value ushr 16 and 0xffL).toByte()
        pointer[position++] = (value ushr 8 and 0xffL).toByte()
        pointer[position++] = (value and 0xffL).toByte()
    }

    public fun putFloat(value: Float): Unit = putInt(value.toRawBits())

    public fun putDouble(value: Double): Unit = putLong(value.toRawBits())
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

public typealias RustBuffer = CPointer<mobile_signer_ffi.cinterop.RustBuffer>

public var RustBuffer.capacity: Long
    get() = pointed.capacity
    set(value) { pointed.capacity = value }
public var RustBuffer.len: Long
    get() = pointed.len
    set(value) { pointed.len = value }
public var RustBuffer.data: Pointer?
    get() = pointed.data
    set(value) { pointed.data = value?.reinterpret() }
public fun RustBuffer.asByteBuffer(): ByteBuffer? {
    require(pointed.len <= Int.MAX_VALUE) {
        val length = pointed.len
        "cannot handle RustBuffer longer than Int.MAX_VALUE bytes: length is $length"
    }
    return ByteBuffer(
        pointed.data?.reinterpret<kotlinx.cinterop.ByteVar>() ?: return null,
        pointed.len.toInt(),
    )
}

public typealias RustBufferByValue = CValue<mobile_signer_ffi.cinterop.RustBuffer>
public fun RustBufferByValue(
    capacity: Long,
    len: Long,
    data: Pointer?,
): RustBufferByValue {
    return cValue<mobile_signer_ffi.cinterop.RustBuffer> {
        this.capacity = capacity
        this.len = len
        this.data = data?.reinterpret()
    }
}
public val RustBufferByValue.capacity: Long
    get() = useContents { capacity }
public val RustBufferByValue.len: Long
    get() = useContents { len }
public val RustBufferByValue.data: Pointer?
    get() = useContents { data }
public fun RustBufferByValue.asByteBuffer(): ByteBuffer? {
    require(len <= Int.MAX_VALUE) {
        val length = len
        "cannot handle RustBuffer longer than Int.MAX_VALUE bytes: length is $length"
    }
    return ByteBuffer(
        data?.reinterpret<kotlinx.cinterop.ByteVar>() ?: return null,
        len.toInt(),
    )
}

internal typealias ForeignBytes = CPointer<mobile_signer_ffi.cinterop.ForeignBytes>
internal var ForeignBytes.len: Int
    get() = pointed.len
    set(value) { pointed.len = value }
internal var ForeignBytes.data: Pointer?
    get() = pointed.data
    set(value) { pointed.data = value?.reinterpret() }

internal typealias ForeignBytesByValue = CValue<mobile_signer_ffi.cinterop.ForeignBytes>
internal val ForeignBytesByValue.len: Int
    get() = useContents { len }
internal val ForeignBytesByValue.data: Pointer?
    get() = useContents { data }

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

internal typealias UniffiRustCallStatus = CPointer<mobile_signer_ffi.cinterop.UniffiRustCallStatus>
internal var UniffiRustCallStatus.code: Byte
    get() = pointed.code
    set(value) { pointed.code = value }
internal var UniffiRustCallStatus.errorBuf: RustBufferByValue
    get() = pointed.errorBuf.readValue()
    set(value) { value.place(pointed.errorBuf.ptr) }

internal typealias UniffiRustCallStatusByValue = CValue<mobile_signer_ffi.cinterop.UniffiRustCallStatus>
internal fun UniffiRustCallStatusByValue(
    code: Byte,
    errorBuf: RustBufferByValue
): UniffiRustCallStatusByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiRustCallStatus> {
        this.code = code
        errorBuf.write(this.errorBuf.rawPtr)
    }
}
internal val UniffiRustCallStatusByValue.code: Byte
    get() = useContents { code }
internal val UniffiRustCallStatusByValue.errorBuf: RustBufferByValue
    get() = useContents { errorBuf.readValue() }

internal object UniffiRustCallStatusHelper {
    fun allocValue() = cValue<mobile_signer_ffi.cinterop.UniffiRustCallStatus>()
    fun <U> withReference(
        block: (UniffiRustCallStatus) -> U
    ): U {
        return memScoped {
            val status = alloc<mobile_signer_ffi.cinterop.UniffiRustCallStatus>()
            block(status.ptr)
        }
    }
}

internal class UniffiHandleMap<T: Any> {
    private val mapLock = kotlinx.atomicfu.locks.ReentrantLock()
    private val map = HashMap<Long, T>()

    // We'll start at 1L to prevent "Null Pointers" in native's `interpretCPointer`
    private val counter: kotlinx.atomicfu.AtomicLong = kotlinx.atomicfu.atomic(1L)

    internal val size: Int
        get() = map.size

    // Insert a new object into the handle map and get a handle for it
    internal fun insert(obj: T): Long {
        val handle = counter.getAndAdd(1)
        syncAccess { map.put(handle, obj) }
        return handle
    }

    // Get an object from the handle map
    internal fun get(handle: Long): T {
        return syncAccess { map.get(handle) } ?: throw InternalException("UniffiHandleMap.get: Invalid handle")
    }

    // Remove an entry from the handlemap and get the Kotlin object back
    internal fun remove(handle: Long): T {
        return syncAccess { map.remove(handle) } ?: throw InternalException("UniffiHandleMap.remove: Invalid handle")
    }

    internal fun <T> syncAccess(block: () -> T): T {
        mapLock.lock()
        try {
            return block()
        } finally {
            mapLock.unlock()
        }
    }
}

internal typealias ByteByReference = CPointer<ByteVar>
internal fun ByteByReference.setValue(value: Byte) {
    this.pointed.value = value
}
internal fun ByteByReference.getValue() : Byte {
    return this.pointed.value
}

internal typealias DoubleByReference = CPointer<DoubleVar>
internal fun DoubleByReference.setValue(value: Double) {
    this.pointed.value = value
}
internal fun DoubleByReference.getValue() : Double {
    return this.pointed.value
}

internal typealias FloatByReference = CPointer<FloatVar>
internal fun FloatByReference.setValue(value: Float) {
    this.pointed.value = value
}
internal fun FloatByReference.getValue() : Float {
    return this.pointed.value
}

internal typealias IntByReference = CPointer<IntVar>
internal fun IntByReference.setValue(value: Int) {
    this.pointed.value = value
}
internal fun IntByReference.getValue() : Int {
    return this.pointed.value
}

internal typealias LongByReference = CPointer<LongVar>
internal fun LongByReference.setValue(value: Long) {
    this.pointed.value = value
}
internal fun LongByReference.getValue() : Long {
    return this.pointed.value
}

internal typealias PointerByReference = CPointer<COpaquePointerVar>
internal fun PointerByReference.setValue(value: Pointer?) {
    this.pointed.value = value
}
internal fun PointerByReference.getValue(): Pointer? {
    return this.pointed.value
}

internal typealias ShortByReference = CPointer<ShortVar>
internal fun ShortByReference.setValue(value: Short) {
    this.pointed.value = value
}
internal fun ShortByReference.getValue(): Short {
    return this.pointed.value
}

// Contains loading, initialization code,
// and the FFI Function declarations.

internal typealias UniffiRustFutureContinuationCallback = mobile_signer_ffi.cinterop.UniffiRustFutureContinuationCallback
internal typealias UniffiForeignFutureFree = mobile_signer_ffi.cinterop.UniffiForeignFutureFree
internal typealias UniffiCallbackInterfaceFree = mobile_signer_ffi.cinterop.UniffiCallbackInterfaceFree
internal typealias UniffiForeignFuture = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFuture>

internal var UniffiForeignFuture.`handle`: Long
    get() = pointed.`handle`
    set(value) {
        pointed.`handle` = value
    }

internal var UniffiForeignFuture.`free`: UniffiForeignFutureFree?
    get() = pointed.`free`
    set(value) {
        pointed.`free` = value
    }


internal fun UniffiForeignFuture.uniffiSetValue(other: UniffiForeignFuture) {
    `handle` = other.`handle`
    `free` = other.`free`
}
internal fun UniffiForeignFuture.uniffiSetValue(other: UniffiForeignFutureUniffiByValue) {
    `handle` = other.`handle`
    `free` = other.`free`
}

internal typealias UniffiForeignFutureUniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFuture>
internal fun UniffiForeignFutureUniffiByValue(
    `handle`: Long,
    `free`: UniffiForeignFutureFree?,
): UniffiForeignFutureUniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFuture> {
        this.`handle` = `handle`
        this.`free` = `free`
    }
}


internal val UniffiForeignFutureUniffiByValue.`handle`: Long
    get() = useContents { `handle` }

internal val UniffiForeignFutureUniffiByValue.`free`: UniffiForeignFutureFree?
    get() = useContents { `free` }

internal typealias UniffiForeignFutureStructU8 = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU8>

internal var UniffiForeignFutureStructU8.`returnValue`: Byte
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructU8.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructU8.uniffiSetValue(other: UniffiForeignFutureStructU8) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU8.uniffiSetValue(other: UniffiForeignFutureStructU8UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU8UniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU8>
internal fun UniffiForeignFutureStructU8UniffiByValue(
    `returnValue`: Byte,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructU8UniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU8> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructU8UniffiByValue.`returnValue`: Byte
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructU8UniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteU8 = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteU8
internal typealias UniffiForeignFutureStructI8 = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI8>

internal var UniffiForeignFutureStructI8.`returnValue`: Byte
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructI8.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructI8.uniffiSetValue(other: UniffiForeignFutureStructI8) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI8.uniffiSetValue(other: UniffiForeignFutureStructI8UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI8UniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI8>
internal fun UniffiForeignFutureStructI8UniffiByValue(
    `returnValue`: Byte,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructI8UniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI8> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructI8UniffiByValue.`returnValue`: Byte
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructI8UniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteI8 = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteI8
internal typealias UniffiForeignFutureStructU16 = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU16>

internal var UniffiForeignFutureStructU16.`returnValue`: Short
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructU16.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructU16.uniffiSetValue(other: UniffiForeignFutureStructU16) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU16.uniffiSetValue(other: UniffiForeignFutureStructU16UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU16UniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU16>
internal fun UniffiForeignFutureStructU16UniffiByValue(
    `returnValue`: Short,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructU16UniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU16> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructU16UniffiByValue.`returnValue`: Short
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructU16UniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteU16 = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteU16
internal typealias UniffiForeignFutureStructI16 = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI16>

internal var UniffiForeignFutureStructI16.`returnValue`: Short
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructI16.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructI16.uniffiSetValue(other: UniffiForeignFutureStructI16) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI16.uniffiSetValue(other: UniffiForeignFutureStructI16UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI16UniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI16>
internal fun UniffiForeignFutureStructI16UniffiByValue(
    `returnValue`: Short,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructI16UniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI16> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructI16UniffiByValue.`returnValue`: Short
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructI16UniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteI16 = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteI16
internal typealias UniffiForeignFutureStructU32 = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU32>

internal var UniffiForeignFutureStructU32.`returnValue`: Int
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructU32.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructU32.uniffiSetValue(other: UniffiForeignFutureStructU32) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU32.uniffiSetValue(other: UniffiForeignFutureStructU32UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU32UniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU32>
internal fun UniffiForeignFutureStructU32UniffiByValue(
    `returnValue`: Int,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructU32UniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU32> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructU32UniffiByValue.`returnValue`: Int
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructU32UniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteU32 = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteU32
internal typealias UniffiForeignFutureStructI32 = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI32>

internal var UniffiForeignFutureStructI32.`returnValue`: Int
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructI32.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructI32.uniffiSetValue(other: UniffiForeignFutureStructI32) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI32.uniffiSetValue(other: UniffiForeignFutureStructI32UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI32UniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI32>
internal fun UniffiForeignFutureStructI32UniffiByValue(
    `returnValue`: Int,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructI32UniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI32> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructI32UniffiByValue.`returnValue`: Int
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructI32UniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteI32 = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteI32
internal typealias UniffiForeignFutureStructU64 = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU64>

internal var UniffiForeignFutureStructU64.`returnValue`: Long
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructU64.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructU64.uniffiSetValue(other: UniffiForeignFutureStructU64) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructU64.uniffiSetValue(other: UniffiForeignFutureStructU64UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructU64UniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU64>
internal fun UniffiForeignFutureStructU64UniffiByValue(
    `returnValue`: Long,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructU64UniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructU64> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructU64UniffiByValue.`returnValue`: Long
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructU64UniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteU64 = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteU64
internal typealias UniffiForeignFutureStructI64 = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI64>

internal var UniffiForeignFutureStructI64.`returnValue`: Long
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructI64.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructI64.uniffiSetValue(other: UniffiForeignFutureStructI64) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructI64.uniffiSetValue(other: UniffiForeignFutureStructI64UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructI64UniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI64>
internal fun UniffiForeignFutureStructI64UniffiByValue(
    `returnValue`: Long,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructI64UniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructI64> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructI64UniffiByValue.`returnValue`: Long
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructI64UniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteI64 = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteI64
internal typealias UniffiForeignFutureStructF32 = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructF32>

internal var UniffiForeignFutureStructF32.`returnValue`: Float
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructF32.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructF32.uniffiSetValue(other: UniffiForeignFutureStructF32) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructF32.uniffiSetValue(other: UniffiForeignFutureStructF32UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructF32UniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructF32>
internal fun UniffiForeignFutureStructF32UniffiByValue(
    `returnValue`: Float,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructF32UniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructF32> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructF32UniffiByValue.`returnValue`: Float
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructF32UniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteF32 = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteF32
internal typealias UniffiForeignFutureStructF64 = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructF64>

internal var UniffiForeignFutureStructF64.`returnValue`: Double
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructF64.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructF64.uniffiSetValue(other: UniffiForeignFutureStructF64) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructF64.uniffiSetValue(other: UniffiForeignFutureStructF64UniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructF64UniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructF64>
internal fun UniffiForeignFutureStructF64UniffiByValue(
    `returnValue`: Double,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructF64UniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructF64> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructF64UniffiByValue.`returnValue`: Double
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructF64UniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteF64 = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteF64
internal typealias UniffiForeignFutureStructPointer = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructPointer>

internal var UniffiForeignFutureStructPointer.`returnValue`: Pointer?
    get() = pointed.`returnValue`
    set(value) {
        pointed.`returnValue` = value
    }

internal var UniffiForeignFutureStructPointer.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructPointer.uniffiSetValue(other: UniffiForeignFutureStructPointer) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructPointer.uniffiSetValue(other: UniffiForeignFutureStructPointerUniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructPointerUniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructPointer>
internal fun UniffiForeignFutureStructPointerUniffiByValue(
    `returnValue`: Pointer?,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructPointerUniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructPointer> {
        this.`returnValue` = `returnValue`
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructPointerUniffiByValue.`returnValue`: Pointer?
    get() = useContents { `returnValue` }

internal val UniffiForeignFutureStructPointerUniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompletePointer = mobile_signer_ffi.cinterop.UniffiForeignFutureCompletePointer
internal typealias UniffiForeignFutureStructRustBuffer = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructRustBuffer>

internal var UniffiForeignFutureStructRustBuffer.`returnValue`: RustBufferByValue
    get() = pointed.`returnValue`.readValue()
    set(value) {
        value.write(pointed.`returnValue`.rawPtr)
    }

internal var UniffiForeignFutureStructRustBuffer.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructRustBuffer.uniffiSetValue(other: UniffiForeignFutureStructRustBuffer) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructRustBuffer.uniffiSetValue(other: UniffiForeignFutureStructRustBufferUniffiByValue) {
    `returnValue` = other.`returnValue`
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructRustBufferUniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructRustBuffer>
internal fun UniffiForeignFutureStructRustBufferUniffiByValue(
    `returnValue`: RustBufferByValue,
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructRustBufferUniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructRustBuffer> {
        `returnValue`.write(this.`returnValue`.rawPtr)
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructRustBufferUniffiByValue.`returnValue`: RustBufferByValue
    get() = useContents { `returnValue`.readValue() }

internal val UniffiForeignFutureStructRustBufferUniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteRustBuffer = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteRustBuffer
internal typealias UniffiForeignFutureStructVoid = CPointer<mobile_signer_ffi.cinterop.UniffiForeignFutureStructVoid>

internal var UniffiForeignFutureStructVoid.`callStatus`: UniffiRustCallStatusByValue
    get() = pointed.`callStatus`.readValue()
    set(value) {
        value.write(pointed.`callStatus`.rawPtr)
    }


internal fun UniffiForeignFutureStructVoid.uniffiSetValue(other: UniffiForeignFutureStructVoid) {
    `callStatus` = other.`callStatus`
}
internal fun UniffiForeignFutureStructVoid.uniffiSetValue(other: UniffiForeignFutureStructVoidUniffiByValue) {
    `callStatus` = other.`callStatus`
}

internal typealias UniffiForeignFutureStructVoidUniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructVoid>
internal fun UniffiForeignFutureStructVoidUniffiByValue(
    `callStatus`: UniffiRustCallStatusByValue,
): UniffiForeignFutureStructVoidUniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructVoid> {
        `callStatus`.write(this.`callStatus`.rawPtr)
    }
}


internal val UniffiForeignFutureStructVoidUniffiByValue.`callStatus`: UniffiRustCallStatusByValue
    get() = useContents { `callStatus`.readValue() }

internal typealias UniffiForeignFutureCompleteVoid = mobile_signer_ffi.cinterop.UniffiForeignFutureCompleteVoid
internal typealias UniffiCallbackInterfacePairingCodesMethod0 = mobile_signer_ffi.cinterop.UniffiCallbackInterfacePairingCodesMethod0
internal typealias UniffiVTableCallbackInterfacePairingCodes = CPointer<mobile_signer_ffi.cinterop.UniffiVTableCallbackInterfacePairingCodes>

internal var UniffiVTableCallbackInterfacePairingCodes.`pairingCode`: UniffiCallbackInterfacePairingCodesMethod0?
    get() = pointed.`pairingCode`
    set(value) {
        pointed.`pairingCode` = value
    }

internal var UniffiVTableCallbackInterfacePairingCodes.`uniffiFree`: UniffiCallbackInterfaceFree?
    get() = pointed.`uniffiFree`
    set(value) {
        pointed.`uniffiFree` = value
    }


internal fun UniffiVTableCallbackInterfacePairingCodes.uniffiSetValue(other: UniffiVTableCallbackInterfacePairingCodes) {
    `pairingCode` = other.`pairingCode`
    `uniffiFree` = other.`uniffiFree`
}
internal fun UniffiVTableCallbackInterfacePairingCodes.uniffiSetValue(other: UniffiVTableCallbackInterfacePairingCodesUniffiByValue) {
    `pairingCode` = other.`pairingCode`
    `uniffiFree` = other.`uniffiFree`
}

internal typealias UniffiVTableCallbackInterfacePairingCodesUniffiByValue = CValue<mobile_signer_ffi.cinterop.UniffiVTableCallbackInterfacePairingCodes>
internal fun UniffiVTableCallbackInterfacePairingCodesUniffiByValue(
    `pairingCode`: UniffiCallbackInterfacePairingCodesMethod0?,
    `uniffiFree`: UniffiCallbackInterfaceFree?,
): UniffiVTableCallbackInterfacePairingCodesUniffiByValue {
    return cValue<mobile_signer_ffi.cinterop.UniffiVTableCallbackInterfacePairingCodes> {
        this.`pairingCode` = `pairingCode`
        this.`uniffiFree` = `uniffiFree`
    }
}


internal val UniffiVTableCallbackInterfacePairingCodesUniffiByValue.`pairingCode`: UniffiCallbackInterfacePairingCodesMethod0?
    get() = useContents { `pairingCode` }

internal val UniffiVTableCallbackInterfacePairingCodesUniffiByValue.`uniffiFree`: UniffiCallbackInterfaceFree?
    get() = useContents { `uniffiFree` }






































































































internal object UniffiLib {
    init {
            uniffiCallbackInterfacePairingCodes.register(this)
    }
    // The Cleaner for the whole library
    internal val CLEANER: UniffiCleaner by lazy {
        UniffiCleaner.create()
    }

    fun uniffi_mobile_signer_ffi_fn_clone_pairingcodes(
        `ptr`: Pointer?,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Pointer? = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_clone_pairingcodes(
        `ptr`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_free_pairingcodes(
        `ptr`: Pointer?,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_free_pairingcodes(
        `ptr`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_init_callback_vtable_pairingcodes(
        `vtable`: UniffiVTableCallbackInterfacePairingCodes,
    ): Unit = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_init_callback_vtable_pairingcodes(
        `vtable`,
    )
    fun uniffi_mobile_signer_ffi_fn_method_pairingcodes_pairing_code(
        `ptr`: Pointer?,
    ): Long = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_method_pairingcodes_pairing_code(
        `ptr`,
    )
    fun uniffi_mobile_signer_ffi_fn_clone_walletlink(
        `ptr`: Pointer?,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Pointer? = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_clone_walletlink(
        `ptr`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_free_walletlink(
        `ptr`: Pointer?,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_free_walletlink(
        `ptr`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_constructor_walletlink_new(
        `wallet`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Pointer? = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_constructor_walletlink_new(
        `wallet`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_method_walletlink_account_xpub(
        `ptr`: Pointer?,
        `derivationPath`: RustBufferByValue,
    ): Long = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_method_walletlink_account_xpub(
        `ptr`,
        `derivationPath`,
    )
    fun uniffi_mobile_signer_ffi_fn_method_walletlink_connect(
        `ptr`: Pointer?,
        `codes`: Pointer?,
    ): Long = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_method_walletlink_connect(
        `ptr`,
        `codes`,
    )
    fun uniffi_mobile_signer_ffi_fn_method_walletlink_disconnect(
        `ptr`: Pointer?,
    ): Long = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_method_walletlink_disconnect(
        `ptr`,
    )
    fun uniffi_mobile_signer_ffi_fn_method_walletlink_info(
        `ptr`: Pointer?,
    ): Long = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_method_walletlink_info(
        `ptr`,
    )
    fun uniffi_mobile_signer_ffi_fn_method_walletlink_sign_psbt(
        `ptr`: Pointer?,
        `psbtBase64`: RustBufferByValue,
        `signerFingerprint`: RustBufferByValue,
    ): Long = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_method_walletlink_sign_psbt(
        `ptr`,
        `psbtBase64`,
        `signerFingerprint`,
    )
    fun uniffi_mobile_signer_ffi_fn_method_walletlink_unlock(
        `ptr`: Pointer?,
    ): Long = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_method_walletlink_unlock(
        `ptr`,
    )
    fun uniffi_mobile_signer_ffi_fn_func_compute_vault_address(
        `borrowerPubkey`: RustBufferByValue,
        `platformPubkey`: RustBufferByValue,
        `lenderPubkey`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_compute_vault_address(
        `borrowerPubkey`,
        `platformPubkey`,
        `lenderPubkey`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_func_compute_vault_address_multisig(
        `xpubs`: RustBufferByValue,
        `threshold`: Byte,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_compute_vault_address_multisig(
        `xpubs`,
        `threshold`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_func_derive_borrower_pubkey(
        `accountXpub`: RustBufferByValue,
        `index`: Int,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_derive_borrower_pubkey(
        `accountXpub`,
        `index`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_func_generate_software_multisig_key(
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `account`: Int,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_generate_software_multisig_key(
        `passphrase`,
        `network`,
        `account`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_func_hot_receive_address(
        `mnemonicWords`: RustBufferByValue,
        `network`: RustBufferByValue,
        `index`: Int,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_hot_receive_address(
        `mnemonicWords`,
        `network`,
        `index`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_func_hot_send(
        `mnemonicWords`: RustBufferByValue,
        `network`: RustBufferByValue,
        `destination`: RustBufferByValue,
        `amountSats`: Long,
        `feeSats`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_hot_send(
        `mnemonicWords`,
        `network`,
        `destination`,
        `amountSats`,
        `feeSats`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_func_hot_wallet_snapshot(
        `mnemonicWords`: RustBufferByValue,
        `network`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_hot_wallet_snapshot(
        `mnemonicWords`,
        `network`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_func_live_wallet_links(
        uniffiCallStatus: UniffiRustCallStatus,
    ): Long = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_live_wallet_links(
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_func_restore_software_multisig_key(
        `mnemonicWords`: RustBufferByValue,
        `passphrase`: RustBufferByValue,
        `network`: RustBufferByValue,
        `account`: Int,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_restore_software_multisig_key(
        `mnemonicWords`,
        `passphrase`,
        `network`,
        `account`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_fn_func_scan_wallets(
        `kind`: RustBufferByValue,
    ): Long = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_scan_wallets(
        `kind`,
    )
    fun uniffi_mobile_signer_ffi_fn_func_sign_psbt(
        `psbtJson`: RustBufferByValue,
        `borrowerPubkey`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_fn_func_sign_psbt(
        `psbtJson`,
        `borrowerPubkey`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rustbuffer_alloc(
        `size`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rustbuffer_alloc(
        `size`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rustbuffer_from_bytes(
        `bytes`: ForeignBytesByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rustbuffer_from_bytes(
        `bytes`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rustbuffer_free(
        `buf`: RustBufferByValue,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rustbuffer_free(
        `buf`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rustbuffer_reserve(
        `buf`: RustBufferByValue,
        `additional`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rustbuffer_reserve(
        `buf`,
        `additional`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_u8(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_u8(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_u8(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_u8(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_u8(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_u8(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_u8(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Byte = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_u8(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_i8(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_i8(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_i8(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_i8(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_i8(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_i8(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_i8(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Byte = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_i8(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_u16(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_u16(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_u16(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_u16(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_u16(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_u16(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_u16(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Short = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_u16(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_i16(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_i16(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_i16(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_i16(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_i16(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_i16(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_i16(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Short = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_i16(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_u32(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_u32(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_u32(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_u32(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_u32(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_u32(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_u32(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Int = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_u32(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_i32(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_i32(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_i32(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_i32(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_i32(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_i32(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_i32(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Int = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_i32(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_u64(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_u64(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_u64(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_u64(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_u64(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_u64(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_u64(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Long = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_u64(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_i64(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_i64(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_i64(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_i64(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_i64(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_i64(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_i64(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Long = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_i64(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_f32(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_f32(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_f32(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_f32(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_f32(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_f32(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_f32(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Float = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_f32(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_f64(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_f64(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_f64(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_f64(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_f64(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_f64(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_f64(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Double = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_f64(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_pointer(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_pointer(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_pointer(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_pointer(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_pointer(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_pointer(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_pointer(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Pointer? = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_pointer(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_rust_buffer(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_rust_buffer(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_rust_buffer(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_rust_buffer(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_rust_buffer(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_rust_buffer(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_rust_buffer(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): RustBufferByValue = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_rust_buffer(
        `handle`,
        uniffiCallStatus,
    )
    fun ffi_mobile_signer_ffi_rust_future_poll_void(
        `handle`: Long,
        `callback`: UniffiRustFutureContinuationCallback,
        `callbackData`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_poll_void(
        `handle`,
        `callback`,
        `callbackData`,
    )
    fun ffi_mobile_signer_ffi_rust_future_cancel_void(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_cancel_void(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_free_void(
        `handle`: Long,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_free_void(
        `handle`,
    )
    fun ffi_mobile_signer_ffi_rust_future_complete_void(
        `handle`: Long,
        uniffiCallStatus: UniffiRustCallStatus,
    ): Unit = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_rust_future_complete_void(
        `handle`,
        uniffiCallStatus,
    )
    fun uniffi_mobile_signer_ffi_checksum_func_compute_vault_address(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_compute_vault_address(
    )
    fun uniffi_mobile_signer_ffi_checksum_func_compute_vault_address_multisig(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_compute_vault_address_multisig(
    )
    fun uniffi_mobile_signer_ffi_checksum_func_derive_borrower_pubkey(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_derive_borrower_pubkey(
    )
    fun uniffi_mobile_signer_ffi_checksum_func_generate_software_multisig_key(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_generate_software_multisig_key(
    )
    fun uniffi_mobile_signer_ffi_checksum_func_hot_receive_address(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_hot_receive_address(
    )
    fun uniffi_mobile_signer_ffi_checksum_func_hot_send(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_hot_send(
    )
    fun uniffi_mobile_signer_ffi_checksum_func_hot_wallet_snapshot(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_hot_wallet_snapshot(
    )
    fun uniffi_mobile_signer_ffi_checksum_func_live_wallet_links(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_live_wallet_links(
    )
    fun uniffi_mobile_signer_ffi_checksum_func_restore_software_multisig_key(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_restore_software_multisig_key(
    )
    fun uniffi_mobile_signer_ffi_checksum_func_scan_wallets(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_scan_wallets(
    )
    fun uniffi_mobile_signer_ffi_checksum_func_sign_psbt(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_func_sign_psbt(
    )
    fun uniffi_mobile_signer_ffi_checksum_method_pairingcodes_pairing_code(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_method_pairingcodes_pairing_code(
    )
    fun uniffi_mobile_signer_ffi_checksum_method_walletlink_account_xpub(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_method_walletlink_account_xpub(
    )
    fun uniffi_mobile_signer_ffi_checksum_method_walletlink_connect(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_method_walletlink_connect(
    )
    fun uniffi_mobile_signer_ffi_checksum_method_walletlink_disconnect(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_method_walletlink_disconnect(
    )
    fun uniffi_mobile_signer_ffi_checksum_method_walletlink_info(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_method_walletlink_info(
    )
    fun uniffi_mobile_signer_ffi_checksum_method_walletlink_sign_psbt(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_method_walletlink_sign_psbt(
    )
    fun uniffi_mobile_signer_ffi_checksum_method_walletlink_unlock(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_method_walletlink_unlock(
    )
    fun uniffi_mobile_signer_ffi_checksum_constructor_walletlink_new(
    ): Short = mobile_signer_ffi.cinterop.uniffi_mobile_signer_ffi_checksum_constructor_walletlink_new(
    )
    fun ffi_mobile_signer_ffi_uniffi_contract_version(
    ): Int = mobile_signer_ffi.cinterop.ffi_mobile_signer_ffi_uniffi_contract_version(
    )
    
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

private class NativeCleaner : UniffiCleaner {
    override fun register(resource: Any, disposable: Disposable): UniffiCleaner.Cleanable =
        // Ignore value here. In Kotlin/Native, if a cleaner object is referenced by the resource
        // object, both objects might leak and cleanupAction won't be called.
        //
        // Since this cleanable instance will be referenced by the resource instance, ignoring
        // the resource and making the cleaner only reference the disposable prevents the cleaner
        // from making a reference cycle thus able to destroy the disposable at a proper moment.
        UniffiNativeCleanable(disposable)
}

private class UniffiNativeCleanable(val disposable: Disposable) : UniffiCleaner.Cleanable {
    private val cleanAction = UniffiNativeCleanAction(disposable)

    @Suppress("unused")
    @OptIn(ExperimentalNativeApi::class)
    private val cleaner = createCleaner(cleanAction, UniffiNativeCleanAction::clean)

    override fun clean() {
        cleanAction.clean()
    }

    private class UniffiNativeCleanAction(private val disposable: Disposable) {
        private val cleaned = atomic(false)
        fun clean() {
            if (cleaned.compareAndSet(false, true)) {
                disposable.destroy()
            }
        }
    }
}

private fun UniffiCleaner.Companion.create(): UniffiCleaner =
    NativeCleaner()


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
    internal fun `pairingCode`(
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
            val uniffiResult = cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructRustBuffer> {
                FfiConverterOptionalString.lower(returnValue).write(this.returnValue.rawPtr)
                UniffiRustCallStatusHelper.allocValue().write(this.callStatus.rawPtr)
            }
            uniffiFutureCallback.invoke(uniffiCallbackData, uniffiResult)
        }
        val uniffiHandleError = { callStatus: UniffiRustCallStatusByValue ->
            val uniffiResult = cValue<mobile_signer_ffi.cinterop.UniffiForeignFutureStructRustBuffer> {
                RustBufferHelper.allocValue().write(this.returnValue.rawPtr)
                callStatus.write(this.callStatus.rawPtr)
            }
            uniffiFutureCallback.invoke(uniffiCallbackData, uniffiResult)
        }

        uniffiOutReturn.uniffiSetValue(
            uniffiTraitInterfaceCallAsync(
                makeCall,
                uniffiHandleSuccess,
                uniffiHandleError
            )
        )
    }
    
    internal fun uniffiFree(handle: Long) {
        FfiConverterTypePairingCodes.handleMap.remove(handle)
    }

    internal val vtable = nativeHeap.alloc<mobile_signer_ffi.cinterop.UniffiVTableCallbackInterfacePairingCodes> {
        @Suppress("UNCHECKED_CAST")
        this.`pairingCode` = staticCFunction {
            `uniffiHandle`: Long,
            `uniffiFutureCallback`: UniffiForeignFutureCompleteRustBuffer,
            `uniffiCallbackData`: Long,
            `uniffiOutReturn`: UniffiForeignFuture,
            ->
            uniffiCallbackInterfacePairingCodes.`pairingCode`(
                `uniffiHandle`,
                `uniffiFutureCallback`,
                `uniffiCallbackData`,
                `uniffiOutReturn`,
            )
        } as mobile_signer_ffi.cinterop.UniffiCallbackInterfacePairingCodesMethod0
        this.uniffiFree = staticCFunction { handle: Long ->
            uniffiCallbackInterfacePairingCodes.uniffiFree(handle)
        }
    }.ptr

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




public object FfiConverterTypeHotWalletSnapshot: FfiConverterRustBuffer<HotWalletSnapshot> {
    override fun read(buf: ByteBuffer): HotWalletSnapshot {
        return HotWalletSnapshot(
            FfiConverterString.read(buf),
            FfiConverterULong.read(buf),
            FfiConverterTypeKeyNetwork.read(buf),
        )
    }

    override fun allocationSize(value: HotWalletSnapshot): ULong = (
            FfiConverterString.allocationSize(value.`receiveAddress`) +
            FfiConverterULong.allocationSize(value.`balanceSats`) +
            FfiConverterTypeKeyNetwork.allocationSize(value.`network`)
    )

    override fun write(value: HotWalletSnapshot, buf: ByteBuffer) {
        FfiConverterString.write(value.`receiveAddress`, buf)
        FfiConverterULong.write(value.`balanceSats`, buf)
        FfiConverterTypeKeyNetwork.write(value.`network`, buf)
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
        }.let { /* this makes the `when` an expression, which ensures it is exhaustive */ }
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
 * First receive address at BIP-84 index `index` (usually 0).
 */
@Throws(MobileSignerException::class)
public actual fun `hotReceiveAddress`(`mnemonicWords`: List<kotlin.String>, `network`: KeyNetwork, `index`: kotlin.UInt): kotlin.String {
    return FfiConverterString.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_receive_address(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterUInt.lower(`index`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Build, sign (BIP-84 index 0 key), and broadcast a payment. Fee is absolute sats.
 */
@Throws(MobileSignerException::class)
public actual fun `hotSend`(`mnemonicWords`: List<kotlin.String>, `network`: KeyNetwork, `destination`: kotlin.String, `amountSats`: kotlin.ULong, `feeSats`: kotlin.ULong): HotSendResult {
    return FfiConverterTypeHotSendResult.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_send(
            FfiConverterSequenceString.lower(`mnemonicWords`),
            FfiConverterTypeKeyNetwork.lower(`network`),
            FfiConverterString.lower(`destination`),
            FfiConverterULong.lower(`amountSats`),
            FfiConverterULong.lower(`feeSats`),
            uniffiRustCallStatus,
        )
    })
}

/**
 * Confirmed + mempool balance for receive index 0 via Esplora.
 */
@Throws(MobileSignerException::class)
public actual fun `hotWalletSnapshot`(`mnemonicWords`: List<kotlin.String>, `network`: KeyNetwork): HotWalletSnapshot {
    return FfiConverterTypeHotWalletSnapshot.lift(uniffiRustCallWithError(MobileSignerExceptionErrorHandler) { uniffiRustCallStatus ->
        UniffiLib.uniffi_mobile_signer_ffi_fn_func_hot_wallet_snapshot(
            FfiConverterSequenceString.lower(`mnemonicWords`),
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

internal val uniffiRustFutureContinuationCallbackCallback = staticCFunction { handle: Long, pollResult: Byte ->
    uniffiContinuationHandleMap.remove(handle).resume(pollResult)
}

internal val uniffiForeignFutureFreeImpl = staticCFunction { handle: Long ->
    val job = uniffiForeignFutureHandleMap.remove(handle)
    if (!job.isCompleted) {
        job.cancel()
    }
}