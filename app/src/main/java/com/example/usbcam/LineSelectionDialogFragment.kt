package com.example.usbcam

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.example.usbcam.api.DepLocationItem
import com.example.usbcam.api.DepTypeItem
import com.example.usbcam.api.DepartmentItem
import com.example.usbcam.api.ReportApiService
import com.example.usbcam.databinding.DialogLineSelectionBinding
import com.example.usbcam.utils.LinePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LineSelectionDialogFragment : DialogFragment() {

    private var _binding: DialogLineSelectionBinding? = null
    private val binding get() = _binding!!

    private val apiService = ReportApiService.create()

    private var factories: List<com.example.usbcam.api.FactoryItem> = emptyList()
    private var depTypes: List<DepTypeItem> = emptyList()
    private var depLocations: List<DepLocationItem> = emptyList()
    private var departments: List<DepartmentItem> = emptyList()

    private var selectedFactory: String? = null
    private var selectedDepType: Int? = null
    private var selectedLocation: String? = null
    private var selectedDepartment: DepartmentItem? = null

    var onLineSelected: ((String) -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogLineSelectionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnSelect.setOnClickListener {
            selectedDepartment?.id?.let { depId ->
                val gxlb = if (selectedDepType == 1) "C+S" else "A"
                
                LinePreferences.saveSelectedLine(requireContext(), depId)
                LinePreferences.saveSelections(
                    requireContext(),
                    selectedFactory,
                    selectedDepType,
                    selectedLocation,
                    gxlb
                )
                onLineSelected?.invoke(depId)
                dismiss()
            } ?: run {
                Toast.makeText(requireContext(), "Vui lòng chọn đơn vị", Toast.LENGTH_SHORT).show()
            }
        }

        setupSpinners()
        fetchFactories()
    }

    private fun setupSpinners() {
        binding.spinnerFactory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    selectedFactory = factories[position - 1].value
                    fetchDepTypes()
                } else {
                    selectedFactory = null
                    resetDepTypeSpinner()
                    resetLocationSpinner()
                    resetDepartmentSpinner()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    selectedDepType = depTypes[position - 1].value
                    fetchDepLocations(selectedDepType!!)
                } else {
                    selectedDepType = null
                    resetLocationSpinner()
                    resetDepartmentSpinner()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerLocation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    selectedLocation = depLocations[position - 1].loc
                    selectedDepType?.let { fetchDepartments(it, selectedLocation!!) }
                } else {
                    selectedLocation = null
                    resetDepartmentSpinner()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.spinnerDepartment.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position > 0) {
                    selectedDepartment = departments[position - 1]
                } else {
                    selectedDepartment = null
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun fetchFactories() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { apiService.getFactory() }
                if (response.isSuccessful && response.body() != null) {
                    factories = response.body()!!
                    val labels = mutableListOf("Chọn nhà máy")
                    labels.addAll(factories.map { it.label ?: "N/A" })
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerFactory.adapter = adapter

                    // Restore saved selection
                    val savedFactory = LinePreferences.getSelectedFactory(requireContext())
                    if (savedFactory != null) {
                        val index = factories.indexOfFirst { it.value == savedFactory }
                        if (index != -1) {
                            binding.spinnerFactory.setSelection(index + 1)
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Lỗi tải danh sách nhà máy", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("LineSelection", "Error fetching Factories", e)
                Toast.makeText(requireContext(), "Lỗi kết nối", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchDepTypes() {
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { apiService.getDepTypes() }
                if (response.isSuccessful && response.body() != null) {
                    depTypes = response.body()!!
                    val labels = mutableListOf("Chọn loại nhà máy")
                    labels.addAll(depTypes.map { getLocalLabel(it.label) })
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerType.adapter = adapter

                    // Restore saved selection
                    val savedType = LinePreferences.getSelectedDepType(requireContext())
                    if (savedType != -1) {
                        val index = depTypes.indexOfFirst { it.value == savedType }
                        if (index != -1) {
                            binding.spinnerType.setSelection(index + 1)
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Lỗi tải loại nhà máy", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("LineSelection", "Error fetching DepTypes", e)
                Toast.makeText(requireContext(), "Lỗi kết nối", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun fetchDepLocations(depType: Int) {
        resetLocationSpinner()
        resetDepartmentSpinner()
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { apiService.getDepLocations(depType) }
                if (response.isSuccessful && response.body() != null) {
                    depLocations = response.body()!!
                    val labels = mutableListOf("Chọn vị trí")
                    labels.addAll(depLocations.map { it.loc ?: "N/A" })
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerLocation.adapter = adapter

                    // Restore saved selection
                    val savedLoc = LinePreferences.getSelectedLocation(requireContext())
                    if (savedLoc != null) {
                        val index = depLocations.indexOfFirst { it.loc == savedLoc }
                        if (index != -1) {
                            binding.spinnerLocation.setSelection(index + 1)
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Lỗi tải vị trí", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("LineSelection", "Error fetching DepLocations", e)
            }
        }
    }

    private fun fetchDepartments(depType: Int, loc: String) {
        resetDepartmentSpinner()
        lifecycleScope.launch {
            try {
                val response = withContext(Dispatchers.IO) { apiService.getDepartments(depType, loc) }
                if (response.isSuccessful && response.body() != null) {
                    departments = response.body()!!
                    val labels = mutableListOf("Chọn đơn vị")
                    labels.addAll(departments.map { it.depName ?: "N/A" })
                    val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, labels)
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    binding.spinnerDepartment.adapter = adapter

                    // Restore saved selection (Department ID)
                    val savedLine = LinePreferences.getSelectedLine(requireContext())
                    if (savedLine != null) {
                        val index = departments.indexOfFirst { it.id == savedLine }
                        if (index != -1) {
                            binding.spinnerDepartment.setSelection(index + 1)
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "Lỗi tải đơn vị", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("LineSelection", "Error fetching Departments", e)
            }
        }
    }

    private fun resetLocationSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf("Chọn vị trí"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLocation.adapter = adapter
        selectedLocation = null
    }

    private fun resetDepTypeSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf("Chọn loại nhà máy"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerType.adapter = adapter
        selectedDepType = null
    }

    private fun resetDepartmentSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf("Chọn đơn vị"))
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerDepartment.adapter = adapter
        selectedDepartment = null
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun getLocalLabel(key: String?): String {
        return when (key) {
            "type_may" -> "May"
            "type_baobi" -> "Bao Bì"
            "type_go" -> "Gò"
            else -> key ?: "Unknown"
        }
    }
}
