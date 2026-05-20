package exh.log

import com.elvishew.xlog.XLog
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

fun OkHttpClient.Builder.maybeInjectEHLogger(): OkHttpClient.Builder {
    if (EHLogLevel.shouldLog(EHLogLevel.EXTREME)) {
        val logger: HttpLoggingInterceptor.Logger =
            object : HttpLoggingInterceptor.Logger {
                override fun log(message: String) {
                    try {
                        Json.parseToJsonElement(message)
                        XLog.tag("||EH-NETWORK-JSON").nst().json(message)
                    } catch (ex: Exception) {
                        XLog.tag("||EH-NETWORK").nb().nst().d(message)
                    }
                }
            }
        return addInterceptor(HttpLoggingInterceptor(logger).apply { level = HttpLoggingInterceptor.Level.BODY })
    }
    return this
}
