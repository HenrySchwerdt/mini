package mini.internal

/*
 * Native surface of the concurrency layer (runtime/src/sys.c, docs/coroutines.md). Convention: see CSymbol. Locks, condition
 * variables and atomics use the elements of a LongArray / IntArray / Array as storage, so reference counting frees them.
 */

@CSymbol("mk_sys_monotonic_ns")
public external fun sysMonotonicNanos(): Long

@CSymbol("mk_sys_wall_ms")
public external fun sysWallMillis(): Long

@CSymbol("mk_sys_sleep_ns")
public external fun sysSleepNanos(ns: Long)

@CSymbol("mk_sys_cpu_count")
public external fun sysCpuCount(): Long

@CSymbol("mk_sys_lock_init")
public external fun sysLockInit(storage: LongArray)

@CSymbol("mk_sys_lock")
public external fun sysLock(storage: LongArray)

@CSymbol("mk_sys_try_lock")
public external fun sysTryLock(storage: LongArray): Boolean

@CSymbol("mk_sys_unlock")
public external fun sysUnlock(storage: LongArray)

@CSymbol("mk_sys_cond_init")
public external fun sysCondInit(storage: LongArray)

@CSymbol("mk_sys_cond_wait")
public external fun sysCondWait(cond: LongArray, lock: LongArray)

@CSymbol("mk_sys_cond_wait_ns")
public external fun sysCondWaitNanos(cond: LongArray, lock: LongArray, ns: Long): Boolean

@CSymbol("mk_sys_cond_signal")
public external fun sysCondSignal(cond: LongArray)

@CSymbol("mk_sys_cond_broadcast")
public external fun sysCondBroadcast(cond: LongArray)

@CSymbol("mk_sys_thread_start")
public external fun sysThreadStart(fn: () -> Unit): Long

@CSymbol("mk_sys_thread_join")
public external fun sysThreadJoin(handle: Long)

@CSymbol("mk_sys_thread_detach")
public external fun sysThreadDetach(handle: Long)

@CSymbol("mk_sys_thread_id")
public external fun sysThreadId(): Long

@CSymbol("mk_sys_thread_data")
public external fun sysThreadData(): Any?

@CSymbol("mk_sys_thread_set_data")
public external fun sysThreadSetData(o: Any?)

@CSymbol("mk_sys_thread_yield")
public external fun sysThreadYield()

@CSymbol("mk_atomic_int_get")
public external fun atomicIntGet(a: IntArray): Int

@CSymbol("mk_atomic_int_set")
public external fun atomicIntSet(a: IntArray, v: Int)

@CSymbol("mk_atomic_int_add")
public external fun atomicIntAdd(a: IntArray, d: Int): Int

@CSymbol("mk_atomic_int_exchange")
public external fun atomicIntExchange(a: IntArray, v: Int): Int

@CSymbol("mk_atomic_int_cas")
public external fun atomicIntCas(a: IntArray, expected: Int, v: Int): Boolean

@CSymbol("mk_atomic_long_get")
public external fun atomicLongGet(a: LongArray): Long

@CSymbol("mk_atomic_long_set")
public external fun atomicLongSet(a: LongArray, v: Long)

@CSymbol("mk_atomic_long_add")
public external fun atomicLongAdd(a: LongArray, d: Long): Long

@CSymbol("mk_atomic_long_exchange")
public external fun atomicLongExchange(a: LongArray, v: Long): Long

@CSymbol("mk_atomic_long_cas")
public external fun atomicLongCas(a: LongArray, expected: Long, v: Long): Boolean

@CSymbol("mk_sys_threads_active")
public external fun threadsActive(): Boolean

@CSymbol("mk_atomic_ref_get")
public external fun atomicRefGet(a: Array<Any?>): Any?

@CSymbol("mk_atomic_ref_set")
public external fun atomicRefSet(a: Array<Any?>, v: Any?)

@CSymbol("mk_atomic_ref_exchange")
public external fun atomicRefExchange(a: Array<Any?>, v: Any?): Any?

@CSymbol("mk_atomic_ref_cas")
public external fun atomicRefCas(a: Array<Any?>, expected: Any?, v: Any?): Boolean
