package com.example.usbcam.api

import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ReportApiService {

    @GET("api/get-data-erp-table")
    suspend fun getDataErpTable(
        @Query("line") line: String
    ): Response<List<ErpTableItem>>

    @GET("api/get-data-erp-header")
    suspend fun getDataErpHeader(
        @Query("line") line: String,
        @Query("date") date: String
    ): Response<List<ErpHeaderItem>>

    @GET("api/get-detail-data-erp")
    suspend fun getDetailDataErp(
        @Query("prono") proNo: String,
        @Query("ry")    ry: String
    ): Response<List<ErpDetailItem>>

    @GET("api/get-data-scan")
    suspend fun getDataScan(
        @Query("line") line: String,
        @Query("date") date: String
    ): Response<List<ScanSummaryItem>>

    @GET("api/get-data-scan-table")
    suspend fun getDataScanTable(
        @Query("line") line: String,
        @Query("date") date: String
    ): Response<List<ScanTableItem>>

    @GET("api/get-data-scan-detail")
    suspend fun getDataScanDetail(
        @Query("line") line: String,
        @Query("date") date: String
    ): Response<List<ScanTableItem>>

    @GET("api/get-report-by-hours")
    suspend fun getReportByHours(
        @Query("line") line: String,
        @Query("date") date: String
    ): Response<List<HourlyReportItem>>

    @GET("api/get-report-by-ry")
    suspend fun getReportByRY(
        @Query("ry") ry: String
    ): Response<List<RyReportItem>>

    @GET("api/get-report-detail-by-ry")
    suspend fun getReportDetailByRY(
        @Query("ry") ry: String
    ): Response<List<RyDetailItem>>

    @GET("api/search-ry")
    suspend fun searchRy(
        @Query("zlbh") zlbh: String,
        @Query("serverCode") serverCode: String
    ): Response<List<SearchRyItem>>

    @GET("api/info-for-ry")
    suspend fun getInfoForRy(
        @Query("ry") ry: String,
        @Query("gxlb") gxlb: String
    ): Response<List<QueueInfoItem>>

    @POST("api/save-stitching-data/{serverCode}")
    suspend fun saveStitchingData(
        @Path("serverCode") serverCode: String,
        @Body body: ScbbContextSaverRequest
    ): Response<SaveStitchingResponse>

    companion object {
        private const val BASE_URL = "http://192.168.30.169:3001/"

        fun create(): ReportApiService {
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(ReportApiService::class.java)
        }
    }
}
