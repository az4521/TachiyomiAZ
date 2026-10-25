package eu.kanade.tachiyomi.network.interceptor

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Catches any uncaught exceptions from later in the chain and rethrows as a non-fatal
 * IOException to avoid catastrophic failure.
 *
 * This should be the first interceptor in the client.
 *
 * See https://square.github.io/okhttp/4.x/okhttp/okhttp3/-interceptor/
 */
class UncaughtExceptionInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(chain.request())
        } catch (e: IOException) {
            throw e
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            // Errors too: an extension built against a newer library can throw a LinkageError
            // here, and OkHttp rethrows anything that isn't an IOException on its dispatcher
            // thread, which takes the whole app down.
            throw IOException(e)
        }
    }
}
