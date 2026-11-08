package com.openlauncher.app.data

import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

data class OpenMeteoResponse(
    @SerializedName("current") val currentData: CurrentData?,
    @SerializedName("daily") val dailyData: DailyData?
)

data class CurrentData(
    @SerializedName("temperature_2m") val temperature: Double,
    @SerializedName("precipitation") val precipitation: Double? = null,
    @SerializedName("wind_speed_10m") val windspeed: Double = 0.0,
    @SerializedName("wind_direction_10m") val winddirection: Double = 0.0,
    @SerializedName("weather_code") val weathercode: Int = 0
)

data class DailyData(
    @SerializedName("time") val dates: List<String>,
    @SerializedName("temperature_2m_max") val maxTemperatures: List<Double>,
    @SerializedName("temperature_2m_min") val minTemperatures: List<Double>,
    @SerializedName("weathercode") val weatherCodes: List<Int>,
    @SerializedName("precipitation_sum") val precipitationSums: List<Double>?,
    @SerializedName("precipitation_probability_max") val precipitationProbabilities: List<Int>?,
    @SerializedName("sunrise") val sunrises: List<String>?,
    @SerializedName("sunset") val sunsets: List<String>?
)

interface WeatherApiService {
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") currentVariables: String = "temperature_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m",
        @Query("daily") dailyVariables: String = "temperature_2m_max,temperature_2m_min,weathercode,precipitation_sum,precipitation_probability_max,sunrise,sunset",
        @Query("timezone") timezone: String = "auto",
        @Query("temperature_unit") temperatureUnit: String = "celsius"
    ): OpenMeteoResponse
}

object WeatherApi {
    private val client = OkHttpClient.Builder().build()

    val service: WeatherApiService = Retrofit.Builder()
        .baseUrl("https://api.open-meteo.com/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(WeatherApiService::class.java)
}

data class NominatimResponse(
    @SerializedName("address") val address: NominatimAddress?,
    @SerializedName("name") val name: String?
)

data class NominatimAddress(
    @SerializedName("city") val city: String?,
    @SerializedName("town") val town: String?,
    @SerializedName("village") val village: String?,
    @SerializedName("suburb") val suburb: String?,
    @SerializedName("municipality") val municipality: String?,
    @SerializedName("county") val county: String?
) {
    fun getCityOrTown(): String? = city ?: town ?: village ?: suburb ?: municipality ?: county
}

interface NominatimApiService {
    @GET("reverse?format=jsonv2")
    suspend fun reverseGeocode(
        @Query("lat") lat: Double,
        @Query("lon") lon: Double,
        @Header("User-Agent") userAgent: String = "OpenLauncherApp/1.0"
    ): NominatimResponse
}

object NominatimApi {
    private val client = OkHttpClient.Builder().build()

    val service: NominatimApiService = Retrofit.Builder()
        .baseUrl("https://nominatim.openstreetmap.org/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(NominatimApiService::class.java)
}
