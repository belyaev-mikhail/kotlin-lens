package ru.spbstu.klens

import org.junit.Test

data class Me(val ii: Int?)

annotation class What(val x: Int)

interface Optic

interface Getter<T, R> : Optic {
    fun T.get(): R
}

interface Setter<T, R> : Optic {
    fun T.map(f: (R) -> R): T
}

interface Iso<T, R> : Getter<T, R> {
    override fun T.get(): R
    fun R.apply(): T
}

interface Lens<T, R> : Getter<T, R>, Setter<T, R> {
    override fun T.get(): R
    fun T.set(value: R): T
    override fun T.map(f: (R) -> R): T = set(f(get()))

    data class Joined<A, B, C> (val left: Lens<A, B>, val right: Lens<B, C>) : Lens<A, C> {
        override fun A.get(): C = right(left(this))

        override fun A.set(value: C): A {
            val self = this /* remember javascript? :-) */
            with(left) {
                with(right) { /* and pray that type inference gods are on our side now */
                    return self.set(self.get().set(value))
                }
            }
        }
    }

    companion object {
        inline fun<T, R> of(name: String,
                            crossinline getter: T.() -> R,
                            crossinline setter: T.(R) -> T) = object: Lens<T, R> {
            override fun toString() = name
            override fun T.get(): R = getter()
            override fun T.set(value: R): T = setter(value)
        }
    }
}

interface Prism<T, R> : Optic {
    fun T.getOption(): R?
    fun R.apply(): T
}

interface Optional<T, R> : Optic {
    fun T.getOption(): R?
    fun T.set(value: R): T
}

interface Traversal<T, R> : Optic {
    fun T.getAll(): Iterable<R>
    fun T.setAll(value: R): T
}

operator fun <T, R> Lens<T, R>.invoke(self: T): R = self.get()
operator fun <T, R> Lens<T, R>.invoke(self: T, value: R) = self.set(value)

infix fun<A, B, C> Lens<A, B>.join(that: Lens<B, C>): Lens<A, C> = Lens.Joined(this, that)

data class Lenser<T, R>(val self: T, val lens: Lens<T, R>) {
    fun get(): R = with(lens) { self.get() }
    inline operator fun invoke(): R = get()
    fun set(value: R) = with(lens) { self.set(value) }
    inline operator fun invoke(value: R) = set(value)
}
fun<T> Lenser(self: T) = Lenser(self, Lens.of<T, T>("id", getter = {this}, setter = {it}))
fun<T, R> Lenser<T, R>.mod(f: (R) -> R): T = set(f(get()))

fun <T, R> Lens<T, R>.toMapping(): (T.((R) -> R) -> T) = { f ->  Lenser(this, this@toMapping).mod(f) }

infix fun<A, B, C> Lenser<A, B>.join(that: Lens<B, C>): Lenser<A, C> =
        Lenser(self, Lens.Joined(lens, that))

val<T, A, B> Lenser<T, Pair<A, B>>.first
    @JvmName("getPairFirst") get() = this join Lens.of<Pair<A, B>, A>(
            "first",
            getter = { first },
            setter = { copy(first = it) }
    )
val<T, A, B> Lenser<T, Pair<A, B>>.second
    @JvmName("getPairSecond") get() = this join Lens.of<Pair<A, B>, B>(
            "second",
            getter = { second },
            setter = { copy(second = it) }
    )

val<T, A, B, C> Lenser<T, Triple<A, B, C>>.first
    @JvmName("getTripleFirst") get() = this join Lens.of<Triple<A, B, C>, A>(
            "first",
            getter = { first },
            setter = { copy(first = it) }
    )

val<T, A, B, C> Lenser<T, Triple<A, B, C>>.second
    @JvmName("getTripleSecond") get() = this join Lens.of<Triple<A, B, C>, B>(
            "second",
            getter = { second },
            setter = { copy(second = it) }
    )

val<T, A, B, C> Lenser<T, Triple<A, B, C>>.third
    @JvmName("getTripleThird") get() = this join Lens.of<Triple<A, B, C>, C>(
            "second",
            getter = { third },
            setter = { copy(third = it) }
    )

@JvmName("genericArrayGet")
operator fun <S, T> Lenser<S, Array<T>>.get(index: Int) =
        this join Lens.of<Array<T>, T>("get",
                getter = { get(index) },
                setter = { clone().apply { set(index, it) } }
        )

@JvmName("stringGet")
operator fun <S> Lenser<S, String>.get(index: Int) =
        this join Lens.of<String, Char>("get",
                getter = { get(index) },
                setter = { StringBuilder(this).apply { setCharAt(index, it) }.toString() }
        )

interface Iteration<T, R> : Setter<T, R> {
    fun T.getAll(): Iterable<R>

    companion object {
        fun<T, R> of(name: String, getter: T.() -> Iterable<R>, setter: T.((R) -> R) -> T) =
                object : Iteration<T, R> {
                    override fun T.map(f: (R) -> R): T = setter(f)
                    override fun T.getAll(): Iterable<R> = getter()
                    override fun toString() = name
                }
    }

    class Id<E> : Iteration<Iterable<E>, E> {
        override fun Iterable<E>.map(f: (E) -> E): Iterable<E> = map<E, E>(f) // forcing kotlin.collections.map
        override fun Iterable<E>.getAll()= this
        override fun toString() = "id"
    }

    data class Join1<A, B, C>(val lens: Lens<A, B>, val iteration: Iteration<B, C>): Iteration<A, C> {
        override fun A.map(f: (C) -> C): A =
                with(lens) {
                    with(iteration) {
                        set(get().map(f))
                    }
                }

        override fun A.getAll(): Iterable<C> =
                with(lens) {
                    with(iteration) {
                        get().getAll()
                    }
                }
    }

    data class Mapping<A, B, C>(val iteration: Iteration<A, B>, val lens: Lens<B, C>): Iteration<A, C> {
        override fun A.map(f: (C) -> C): A =
                with(lens) {
                    with(iteration) {
                        map { it.map(f) }
                    }
                }

        override fun A.getAll(): Iterable<C> =
                with(lens) {
                    with(iteration) {
                        getAll().map { it.get() }
                    }
                }
    }

}

data class Argument(val name: String, val type: String)

class DeclarationContext {
    fun `fun`(body: (Argument) -> )
}

class Playground {
    @Test
    fun `have fun`() {

        val x = arrayOf(Pair(2, Triple(Pair(2, 3.15), "Hello", null)))
        println("x = ${x.toList()}")
        val y = Lenser(x)[0].second.first.second.set(4.2e2)
        println("y = ${y.toList()}")
        val z = Lenser(x)[0].second.third()
        println("z = ${z}")
        val zz = Lenser(x)[0]()
        println(zz)


        val yyy = Lenser(x)[0].second.second[3].set('a')
        println(yyy.toList())
    }
}
