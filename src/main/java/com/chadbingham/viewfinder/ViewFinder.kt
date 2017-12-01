@file:Suppress("UNCHECKED_CAST", "unused")

package com.chadbingham.viewfinder

import android.app.Activity
import android.app.Dialog
import android.support.v4.app.DialogFragment
import android.support.v4.app.Fragment
import android.support.v7.widget.RecyclerView
import android.support.v7.widget.RecyclerView.Adapter
import android.support.v7.widget.RecyclerView.ViewHolder
import android.util.Log
import android.view.View
import com.chadbingham.viewfinder.BuildConfig.DEBUG
import com.chadbingham.viewfinder.Registry.register
import com.chadbingham.viewfinder.ViewFinder.TAG
import java.util.*
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

object ViewFinder {
    const val TAG = "ViewFinder"
    var logging = false
}

fun View.dropViews() = Registry.drop(this)
fun Activity.dropViews() = Registry.drop(this)
fun Dialog.dropViews() = Registry.drop(this)
fun DialogFragment.dropViews() = Registry.drop(this)
fun Fragment.dropViews() = Registry.drop(this)
fun ViewHolder.dropViews() = Registry.drop(this)

fun <V : View> View.find(id: Int): ReadOnlyProperty<View, V> = find(id, finder)
fun <V : View> Activity.find(id: Int): ReadOnlyProperty<Activity, V> = find(id, finder)
fun <V : View> Dialog.find(id: Int): ReadOnlyProperty<Dialog, V> = find(id, finder)
fun <V : View> DialogFragment.find(id: Int): ReadOnlyProperty<DialogFragment, V> = find(id, finder)
fun <V : View> Fragment.find(id: Int): ReadOnlyProperty<Fragment, V> = find(id, finder)
fun <V : View> ViewHolder.find(id: Int): ReadOnlyProperty<ViewHolder, V> = find(id, finder)

fun <V : View> View.findOptional(id: Int): ReadOnlyProperty<View, V?> = optional(id, finder)
fun <V : View> Activity.findOptional(id: Int): ReadOnlyProperty<Activity, V?> = optional(id, finder)
fun <V : View> Dialog.findOptional(id: Int): ReadOnlyProperty<Dialog, V?> = optional(id, finder)
fun <V : View> DialogFragment.findOptional(id: Int): ReadOnlyProperty<DialogFragment, V?> = optional(id, finder)
fun <V : View> Fragment.findOptional(id: Int): ReadOnlyProperty<Fragment, V?> = optional(id, finder)
fun <V : View> ViewHolder.findOptional(id: Int): ReadOnlyProperty<ViewHolder, V?> = optional(id, finder)

fun View.showWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, true)
fun Activity.showWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, true)
fun Dialog.showWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, true)
fun DialogFragment.showWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, true)
fun Fragment.showWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, true)
fun ViewHolder.showWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, true)
fun View.hideWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, false)
fun Activity.hideWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, false)
fun Dialog.hideWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, false)
fun DialogFragment.hideWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, false)
fun Fragment.hideWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, false)
fun ViewHolder.hideWhenEmpty(view: View, adapter: Adapter<*>) = observer(view, adapter, false)

private inline fun <reified T : Any> T.observer(view: View, adapter: Adapter<*>, show: Boolean) {
    register(this, DataObserver(view, adapter, show))
}

private val View.finder: View.(Int) -> View?
    get() = { findViewById(it) }

private val Activity.finder: Activity.(Int) -> View?
    get() = { findViewById(it) }

private val Dialog.finder: Dialog.(Int) -> View?
    get() = { findViewById(it) }

private val DialogFragment.finder: DialogFragment.(Int) -> View?
    get() = { dialog?.findViewById(it) ?: view?.findViewById(it) }

private val Fragment.finder: Fragment.(Int) -> View?
    get() = { view?.findViewById(it) }

private val ViewHolder.finder: ViewHolder.(Int) -> View?
    get() = { itemView.findViewById(it) }

private fun viewNotFound(id: Int, desc: KProperty<*>): Nothing {
    throw IllegalStateException("View ID $id for '${desc.name}' not found.")
}

private fun <T, V : View> find(id: Int, finder: T.(Int) -> View?): Finder<T, V> {
    return Finder(id) { t: T, desc -> t.finder(id) as V? ?: viewNotFound(id, desc) }
}

private fun <T, V : View> optional(id: Int, finder: T.(Int) -> View?): Finder<T, V?> {
    return Finder(id) { t: T, _ -> t.finder(id) as V? }
}

private interface TargetReference {
    fun drop()
}

private object Registry {
    private val targets = WeakHashMap<Any, TargetViewBindings>()

    fun register(target: Any, reference: TargetReference) {
        if (targets[target] != null) {
            log("Binder found for target: $target")
        }

        val binding = targets[target] ?: TargetViewBindings(target)
        binding.register(reference)
        targets.put(target, binding)
    }

    fun drop(target: Any) {
        val binding = targets[target]
        if (binding != null) {
            log("Dropping TargetReference for $target. $binding")
            binding.drop()
            targets.remove(binding)
        } else {
            w("No TargetReference to drop for $target")
        }
    }
}

private data class TargetViewBindings(private val target: Any) : TargetReference {

    private val finders = WeakHashMap<Int, TargetReference>()
    private val observers = WeakHashMap<Int, TargetReference>()

    fun register(reference: TargetReference) {
        if (reference is Finder<*, *>) {
            finders[reference.id] = reference
            log("${finders.size} finders registered in $this")
        } else if (reference is DataObserver) {
            observers[reference.id] = reference
            log("${observers.size} observers registered in $this")
        }
    }

    override fun drop() {
        finders.values.forEach { it.drop() }
        finders.clear()
        observers.values.forEach { it.drop() }
        observers.clear()
    }

    override fun toString(): String {
        return "TargetViewBindings[finders=[${finders.size}], observers=[${observers.size}]]"
    }
}

private data class Finder<in T, out V>(val id: Int, val initializer: (T, KProperty<*>) -> V)
    : ReadOnlyProperty<T, V>, TargetReference {

    private object EMPTY

    private var value: Any? = EMPTY

    private var name: String? = null

    override fun getValue(thisRef: T, property: KProperty<*>): V {
        name = property.name
        thisRef?.let {
            register(thisRef, this)
        }

        if (value == EMPTY) {
            value = initializer(thisRef, property)
        }

        return value as V
    }

    override fun drop() {
        if (name != null && value != null)
            log("releasing '$name'(${value!!::class.java.simpleName})")
        value = EMPTY
    }
}

private class DataObserver(
        private val target: View,
        private val adapter: Adapter<*>,
        private val show: Boolean = true)
    : RecyclerView.AdapterDataObserver(), TargetReference {

    val id = target.id

    init {
        adapter.registerAdapterDataObserver(this)
        showTarget()
    }

    override fun drop() {
        log("Unregistering data observer")
        adapter.unregisterAdapterDataObserver(this)
    }

    private fun showTarget() {
        target.visibility = if (adapter.itemCount == 0) {
            if (show)
                View.VISIBLE
            else
                View.GONE
        } else {
            if (show)
                View.GONE
            else
                View.VISIBLE
        }
    }

    override fun onChanged() = showTarget()
    override fun onItemRangeRemoved(posStart: Int, itemCount: Int) = showTarget()
    override fun onItemRangeMoved(fromPos: Int, toPos: Int, itemCount: Int) = showTarget()
    override fun onItemRangeInserted(posStart: Int, itemCount: Int) = showTarget()
    override fun onItemRangeChanged(posStart: Int, itemCount: Int) = showTarget()
    override fun onItemRangeChanged(posStart: Int, itemCount: Int, payload: Any?) = showTarget()
}

private fun log(message: String) {
    if (ViewFinder.logging && DEBUG)
        Log.i(TAG, message)
}

private fun w(message: String) {
    Log.w(TAG, message)
}
