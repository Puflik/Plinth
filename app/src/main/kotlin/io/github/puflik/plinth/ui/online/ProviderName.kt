package io.github.puflik.plinth.ui.online

/**
 * Как провайдер называется на экране: ядро знает его по ID (`archive.org`),
 * человек — по имени. Незнакомый — своим ID. Имена собственные не переводятся.
 */
fun providerName(provider: String): String = NAMES[provider] ?: provider

private val NAMES = mapOf("archive.org" to "Internet Archive")
