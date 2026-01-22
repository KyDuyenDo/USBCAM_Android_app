package com.example.usbcam.rfid

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.example.usbcam.R
import android.content.Context
import android.view.inputmethod.InputMethodManager

/**
 * RfidHidFragment
 *
 * Xử lý dữ liệu từ đầu đọc RFID ở chế độ HID (Keyboard Emulator).
 * - Tự động focus ô nhập liệu.
 * - Chặn nhập liệu từ bàn phím ảo (chỉ nhận từ phần cứng).
 * - Xử lý mã quét, tránh trùng lặp và dính mã.
 */
class RfidHidFragment : DialogFragment() {

    interface RfidCallback {
        fun onRfidRead(code: String)
    }

    private var callback: RfidCallback? = null
    private lateinit var etRfidInput: EditText
    private lateinit var tvLastCode: TextView
    private lateinit var llLastResult: View

    private var lastProcessedCode: String = ""
    private var lastProcessedTime: Long = 0L
    private val DEBOUNCE_TIME_MS = 1000L // 1 giây giữa các lần quét cùng mã

    fun setCallback(callback: RfidCallback) {
        this.callback = callback
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setLayout(
                    (resources.displayMetrics.widthPixels * 0.90).toInt(),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_rfid_hid, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        etRfidInput = view.findViewById(R.id.et_rfid_input)
        tvLastCode = view.findViewById(R.id.tv_last_code)
        llLastResult = view.findViewById(R.id.ll_last_result)

        // ẨN BÀN PHÍM NGAY KHI VIEW ĐƯỢC TẠO
        view.post { hideKeyboard() }

        setupInputLogic()

        view.findViewById<View>(R.id.btn_close).setOnClickListener { dismiss() }
    }

    private fun setupInputLogic() {
        // 1. ẨN BÀN PHÍM CỨNG VÀ MỀM
        etRfidInput.inputType = InputType.TYPE_NULL
        etRfidInput.showSoftInputOnFocus = false
        etRfidInput.isFocusableInTouchMode = true
        etRfidInput.setTextIsSelectable(false)
        etRfidInput.isLongClickable = false

        // 2. ẨN BÀN PHÍM KHI FOCUS (Phương pháp mạnh nhất)
        etRfidInput.setOnTouchListener { v, _ ->
            v.requestFocus()
            hideKeyboard()
            true // Chặn touch event tránh bàn phím bật
        }

        etRfidInput.onFocusChangeListener =
                View.OnFocusChangeListener { v, hasFocus ->
                    if (hasFocus) {
                        hideKeyboard() // Ẩn ngay khi được focus
                    } else {
                        v.post {
                            v.requestFocus()
                            hideKeyboard()
                        }
                    }
                }

        etRfidInput.requestFocus()
        hideKeyboard() // Ẩn ngay từ đầu

        // 3. XỬ LÝ INPUT TỪ HID
        etRfidInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                processRfidInput()
                true
            } else {
                false
            }
        }

        etRfidInput.setOnEditorActionListener { _, actionId, _ ->
            hideKeyboard() // Ẩn bàn phím nếu có
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_NULL) {
                processRfidInput()
                true
            } else false
        }

        etRfidInput.addTextChangedListener(
                object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        s?.toString()?.let { input ->
                            if (input.contains("\n") || input.contains("\r")) {
                                processRfidInput()
                            }
                        }
                    }
                    override fun beforeTextChanged(
                            s: CharSequence?,
                            start: Int,
                            count: Int,
                            after: Int
                    ) {}
                    override fun onTextChanged(
                            s: CharSequence?,
                            start: Int,
                            before: Int,
                            count: Int
                    ) {}
                }
        )
    }

    // HÀM ẨN BÀN PHÍM
    private fun hideKeyboard() {
        val imm =
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as
                        InputMethodManager
        imm.hideSoftInputFromWindow(etRfidInput.windowToken, 0)
    }
    private fun processRfidInput() {
        val rawInput = etRfidInput.text.toString().trim()

        // Làm sạch mã (loại bỏ xuống dòng và khoảng trắng)
        val cleanCode = rawInput.replace("\n", "").replace("\r", "").trim()

        if (cleanCode.isEmpty()) {
            etRfidInput.setText("")
            return
        }

        val currentTime = System.currentTimeMillis()

        // Kiểm tra chặn trùng mã trong khoảng thời gian debounce
        if (cleanCode == lastProcessedCode && (currentTime - lastProcessedTime) < DEBOUNCE_TIME_MS
        ) {
            Log.d("RfidHid", "Chặn mã trùng (Debounce): $cleanCode")
            etRfidInput.setText("")
            return
        }

        // Kiểm tra tránh dính mã (ví dụ mã quá dài bất thường)
        // Tùy chỉnh độ dài mã theo thực tế đầu đọc/thẻ của bạn
        if (cleanCode.length > 32) {
            Log.e("RfidHid", "Mã không hợp lệ hoặc bị dính: $cleanCode")
            etRfidInput.setText("")
            return
        }

        // Cập nhật UI
        lastProcessedCode = cleanCode
        lastProcessedTime = currentTime

        tvLastCode.text = cleanCode
        llLastResult.visibility = View.VISIBLE

        Log.i("RfidHid", "Quét thành công: $cleanCode")

        // Trả kết quả về qua callback
        callback?.onRfidRead(cleanCode)

        // Reset input để sẵn sàng lần tiếp theo
        etRfidInput.setText("")
    }

    override fun onResume() {
        super.onResume()

        // Đảm bảo ẩn bàn phím khi quay lại màn hình
        Handler(Looper.getMainLooper())
                .postDelayed(
                        {
                            etRfidInput.requestFocus()
                            hideKeyboard()
                        },
                        100
                ) // Giảm từ 200ms xuống 100ms

        // Double-check sau 300ms (phòng trường hợp delay)
        Handler(Looper.getMainLooper()).postDelayed({ hideKeyboard() }, 300)
    }

    companion object {
        fun newInstance() = RfidHidFragment()
    }
}
