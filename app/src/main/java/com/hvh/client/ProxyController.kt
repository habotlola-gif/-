package com.hvh.client

/**
 * Управление прокси. Ядро ретранслятора (подключение игры, вход Microsoft, пересылка пакетов)
 * ещё не встроено в приложение — пока контроллер только проверяет данные и сообщает об этом.
 */
object ProxyController {
    const val LISTEN_PORT = 19133

    sealed class Result {
        data class Started(val info: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    var running = false
        private set

    fun start(server: Server): Result {
        return Result.Failed("Ядро прокси ещё не встроено (сервер ${server.address} сохранён)")
    }

    fun stop() { running = false }
}
