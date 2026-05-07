package com.gpunch.api

import com.gpunch.BuildConfig
import com.gpunch.utils.SessionManager
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit

object RetrofitClient {

    private var retrofit: Retrofit? = null

    fun getInstance(sessionManager: SessionManager? = null): Retrofit {
        if (retrofit == null) {
            val loggingInterceptor = HttpLoggingInterceptor().apply {
                redactHeader("Authorization")
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }

            val authInterceptor = Interceptor { chain ->
                val original = chain.request()
                val token = sessionManager?.getToken()
                val request = if (!token.isNullOrBlank()) {
                    original.newBuilder()
                        .header("Authorization", "Bearer $token")
                        .build()
                } else {
                    original
                }
                chain.proceed(request)
            }

            val client = OkHttpClient.Builder()
                .dns(codespacesSafeDns())
                .addInterceptor(authInterceptor)
                .addInterceptor(loggingInterceptor)
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            retrofit = Retrofit.Builder()
                .baseUrl(normalizedBaseUrl(BuildConfig.BASE_URL))
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        }
        return retrofit!!
    }

    /**
     * Create a new instance with updated session (e.g. after login).
     */
    fun resetInstance() {
        retrofit = null
    }

    private fun normalizedBaseUrl(url: String): String =
        if (url.endsWith("/")) url else "$url/"

    private fun codespacesSafeDns(): Dns {
        val cloudflareDns = DnsOverHttps.Builder()
            .client(OkHttpClient.Builder().build())
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(
                InetAddress.getByName("1.1.1.1"),
                InetAddress.getByName("1.0.0.1"),
                InetAddress.getByName("2606:4700:4700::1111"),
                InetAddress.getByName("2606:4700:4700::1001")
            )
            .build()

        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                return try {
                    cloudflareDns.lookup(hostname)
                } catch (_: Exception) {
                    Dns.SYSTEM.lookup(hostname)
                }
            }
        }
    }
}
