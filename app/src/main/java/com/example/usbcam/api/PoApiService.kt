package com.example.usbcam.api

import retrofit2.Call
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import retrofit2.http.GET
import retrofit2.http.Query

interface PoApiService {
    @GET("api/select-po")
    fun getPoDetails(@Query("po") po: String, @Query("barcode") barcode: String): Call<PoResponse>

    @GET("api/select-po")
    suspend fun getPoDetailsSuspend(
            @Query("po") po: String,
            @Query("barcode") barcode: String
    ): retrofit2.Response<PoResponse>

    @GET("api/select-po") fun getPoDetails(@Query("po") po: String): Call<PoResponse>

    @GET("api/info-rfid")
    fun getRfidInfo(@Query("rfid") rfid: String): Call<DataRfid>

    @GET("api/info-rfid")
    suspend fun getRfidInfoSuspend(@Query("rfid") rfid: String): retrofit2.Response<DataRfid>

    @GET("api/target-value")
    suspend fun getTargetByLean(
        @Query("depno") depno: String
    ): retrofit2.Response<TargetResponse>

    @retrofit2.http.POST("api/sync/detail")
    suspend fun syncDetail(
            @retrofit2.http.Body detail: com.example.usbcam.data.model.ShoeboxDetail
    ): retrofit2.Response<Void>

    @retrofit2.http.POST("api/sync/total")
    suspend fun syncTotal(
            @retrofit2.http.Body total: com.example.usbcam.data.model.ShoeboxTotal
    ): retrofit2.Response<Void>


    @retrofit2.http.POST("api/sync/rfid-mismatch")
    suspend fun syncRfidMismatch(
            @retrofit2.http.Body rfidDetail: com.example.usbcam.data.model.ShoeboxDetailRfid
    ): retrofit2.Response<Void>

    @retrofit2.http.POST("api/device-status")
    suspend fun reportDeviceStatus(
        @retrofit2.http.Body status: DeviceStatusRequest
    ): retrofit2.Response<Void>

    @GET("api/ping_device")
    suspend fun pingDevice(
        @Query("line_id") lineId: String
    ): retrofit2.Response<Void>

    /** Load toàn bộ thông tin hộp giày theo trang — dùng để build local cache */
    @GET("api/all-info-box")
    suspend fun getAllInfoBox(
        @Query("page")     page: Int,
        @Query("pagesize") pageSize: Int
    ): retrofit2.Response<AllInfoBoxResponse>

    companion object {
        fun create(): PoApiService {
            val client = OkHttpClient.Builder()
                .connectTimeout(120, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(ApiConfig.BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(PoApiService::class.java)
        }
    }
}
