package com.example.usbcam.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.usbcam.api.ReportApiService
import com.example.usbcam.data.db.AppDatabase
import com.example.usbcam.data.model.DepLocationEntity
import com.example.usbcam.data.model.DepTypeEntity
import com.example.usbcam.data.model.DepartmentEntity
import com.example.usbcam.data.model.FactoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Worker tải toàn bộ dữ liệu cấu hình (Xưởng, Bộ phận, Vị trí) từ API
 * và lưu vào SQLite để sử dụng offline và tăng tốc độ khởi động.
 */
class ConfigCacheWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "ConfigCacheWorker"
        const val WORK_NAME = "ConfigCacheWork"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val db = AppDatabase.getDatabase(applicationContext)
        val dao = db.configCacheDao()
        val apiService = ReportApiService.create()

        try {
            Log.i(TAG, "Bắt đầu tải dữ liệu cấu hình hệ thống...")

            // 1. Tải danh sách xưởng (Factory)
            val factoryResponse = apiService.getFactory()
            if (factoryResponse.isSuccessful) {
                val factories = factoryResponse.body()?.map { 
                    FactoryEntity(value = it.value ?: "", label = it.label) 
                } ?: emptyList()
                dao.insertFactories(factories)
                Log.d(TAG, "Đã lưu ${factories.size} xưởng")
            }

            // 2. Tải danh sách loại bộ phận (DepType)
            val depTypeResponse = apiService.getDepTypes()
            if (depTypeResponse.isSuccessful) {
                val depTypes = depTypeResponse.body() ?: emptyList()
                dao.insertDepTypes(depTypes.map { 
                    DepTypeEntity(value = it.value ?: -1, label = it.label) 
                })
                Log.d(TAG, "Đã lưu ${depTypes.size} loại bộ phận")

                // 3. Tải Vị trí và Bộ phận theo từng loại
                for (type in depTypes) {
                    val typeId = type.value ?: continue
                    
                    // Tải vị trí (Location)
                    val locResponse = apiService.getDepLocations(typeId)
                    if (locResponse.isSuccessful) {
                        val locations = locResponse.body() ?: emptyList()
                        dao.insertDepLocations(locations.map { 
                            DepLocationEntity(depType = typeId, loc = it.loc) 
                        })

                        // Tải bộ phận (Department) theo từng vị trí
                        for (loc in locations) {
                            val locName = loc.loc ?: continue
                            val depResponse = apiService.getDepartments(typeId, locName)
                            if (depResponse.isSuccessful) {
                                val departments = depResponse.body() ?: emptyList()
                                dao.insertDepartments(departments.map { 
                                    DepartmentEntity(
                                        id = it.id ?: "",
                                        depName = it.depName,
                                        depType = typeId,
                                        loc = locName
                                    )
                                })
                            }
                        }
                    }
                }
            }

            Log.i(TAG, "✅ Tải dữ liệu cấu hình hoàn tất")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Lỗi khi tải dữ liệu cấu hình: ${e.message}", e)
            Result.retry()
        }
    }
}
