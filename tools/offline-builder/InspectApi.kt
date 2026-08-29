package de.rdoe.weeklydjshows.tools

object InspectApi {
    @JvmStatic
    fun main(args: Array<String>) {
        args.forEach { name ->
            val type = Class.forName(name)
            println("TYPE $name")
            type.declaredConstructors.forEach { println("CTOR $it") }
            type.declaredMethods.sortedBy { it.name }.forEach { println("METHOD $it") }
            type.declaredFields.sortedBy { it.name }.forEach { println("FIELD $it") }
        }
    }
}
