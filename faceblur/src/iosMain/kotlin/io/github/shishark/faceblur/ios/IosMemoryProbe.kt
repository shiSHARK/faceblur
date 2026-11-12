// SPDX-License-Identifier: Apache-2.0
@file:OptIn(ExperimentalForeignApi::class, NativeRuntimeApi::class)

package io.github.shishark.faceblur.ios

import io.github.shishark.faceblur.mask.MemoryProbe
import io.github.shishark.faceblur.mask.MemoryRule
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.darwin.KERN_SUCCESS
import platform.darwin.TASK_VM_INFO
import platform.darwin.mach_msg_type_number_tVar
import platform.darwin.mach_task_self_
import platform.darwin.task_info
import platform.darwin.task_vm_info_data_t
import platform.posix.getenv
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

/**
 * The iOS memory probe of the mask stage (ADR 0013 decision 6): the memory the system still grants this process
 * (`task_info(TASK_VM_INFO).limit_bytes_remaining`, the figure `os_proc_available_memory()` returns), and `GC.collect()` as the one collection before a refusal. The rule is
 * [MemoryRule.AVAILABLE_ON_SIMULATOR] in the simulator (where the figure is 0 because the process is not a phone app,
 * and the check is skipped) and [MemoryRule.AVAILABLE_ON_DEVICE] otherwise (a figure of 0 is refused).
 */
internal object IosMemoryProbe : MemoryProbe {
    override val rule: MemoryRule =
        if (isSimulator()) MemoryRule.AVAILABLE_ON_SIMULATOR else MemoryRule.AVAILABLE_ON_DEVICE

    override fun figure(): Long = memScoped {
        val info = alloc<task_vm_info_data_t>()
        val count = alloc<mach_msg_type_number_tVar>()
        count.value = (sizeOf<task_vm_info_data_t>() / 4).toUInt() // TASK_VM_INFO_COUNT, in natural_t units
        val kr = task_info(mach_task_self_, TASK_VM_INFO.toUInt(), info.ptr.reinterpret(), count.ptr)
        if (kr != KERN_SUCCESS) 0L else info.limit_bytes_remaining.toLong()
    }

    override fun collect() = GC.collect()

    /** The simulator sets `SIMULATOR_DEVICE_NAME` for every process it runs; a device never does. */
    internal fun isSimulator(): Boolean = getenv("SIMULATOR_DEVICE_NAME")?.toKString()?.isNotEmpty() == true
}
