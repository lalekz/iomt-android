package com.iomt.android.ui.device

import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.iomt.android.*
import com.iomt.android.config.ConfigParser
import com.iomt.android.config.configs.DeviceConfig
import com.iomt.android.entities.Characteristic
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import org.json.JSONObject
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.*
import kotlin.experimental.and

class DeviceFragment : Fragment() {
    private var gatt: BluetoothGatt? = null
    private var mqttAndroidClient: MqttAndroidClient? = null
    private var db: DatabaseHelper? = null
    private val data = Collections.synchronizedList(ArrayList<CharCell>())
    private var senderService: SenderService? = null
    private var devStatus: TextView? = null
    private var devName: TextView? = null
    private var devStPict: ImageView? = null
    private lateinit var deviceConfig: DeviceConfig
    private var inspRate: TextView? = null
    private var expRate: TextView? = null

    private val characteristics: MutableMap<String, Characteristic> = mutableMapOf()

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val period10Minutes = 10
        val periodInMilliseconds = period10Minutes * 60 * 1000
        senderService = SenderService(requireContext(), periodInMilliseconds)
        mqttAndroidClient = MqttAndroidClient(requireContext(), "${R.string.base_uri}:${R.string.mqtt_port}", "")
        mqttAndroidClient!!.setCallback(object : MqttCallback {
            override fun connectionLost(cause: Throwable) {
                Log.d(TAG, "Connection was lost!")
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                Log.d(TAG, "Message Arrived!: " + topic + ": " + String(message.payload))
            }

            override fun deliveryComplete(token: IMqttDeliveryToken) {
                Log.d(TAG, "Delivery Complete!")
            }
        })
    }

    private fun getIcon(charName: String): ImageView =
        ImageView(context).apply {
            setBackgroundResource(R.drawable.circular_grey_bordersolid)
            when (charName) {
                "heartRate" -> R.drawable.heart
                "inspRate", "inspirationRate" -> R.drawable.insp
                "expRate", "expirationRate" -> R.drawable.exp
                "steps", "stepsCount" -> R.drawable.steps
                "activity", "activityRate" -> R.drawable.act
                "cadence" -> R.drawable.cadence
                "battery", "batteryRate" -> R.drawable.battery
                else -> R.drawable.circular_grey_bordersolid
            }.let {
                setImageResource(it)
            }
            maxWidth = 50
            maxHeight = 50
        }

    private fun createCellLayout(charName: String, prettyCharName: String): Pair<LinearLayout, TextView> {
        val charNameBadge = TextView(context).apply { text = prettyCharName }
        val valueBadge = TextView(context)
        val innerLayout = LinearLayout(context).apply {
            addView(charNameBadge)
            addView(valueBadge)
        }
        val icon = getIcon(charName)
        val linearLayout = LinearLayout(context).apply {
            addView(icon)
            addView(innerLayout)
        }
        return linearLayout to valueBadge
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_device, container, false)
        setHasOptionsMenu(true)
        //val toolbar = view.findViewById<View>(R.id.toolbar_dev) as Toolbar

        val configString: String = requireActivity().intent.getStringExtra("deviceConfig")!!
        deviceConfig = ConfigParser().parseFromString(configString)
        val device = requireActivity().intent.getParcelableExtra<BluetoothDevice>("Device")
        (activity as AppCompatActivity).supportActionBar?.title = device?.name
        val editor = requireContext().getSharedPreferences(
            requireContext().getString(R.string.ACC_DATA),
            Context.MODE_PRIVATE
        ).edit()
        editor.putString("DeviceId", device?.address)
        editor.apply()

        when(deviceConfig.general.type) {
            "Vest" -> R.drawable.hexoskin
            "Band" -> R.drawable.band_icon
            else -> R.drawable.circular_grey_bordersolid
        }.let {
            view.findViewById<ImageView>(R.id.deviceIcon)?.setImageResource(it)
        }

        val containerLayout: LinearLayout = view.findViewById(R.id.data_container)
        deviceConfig.general.characteristicNames.forEach { name ->
            val (layout, textView) = createCellLayout(name, deviceConfig.characteristics[name]?.name ?: "")
            containerLayout.addView(layout)
            characteristics[name] = Characteristic(textView)
        }

        devStatus = view.findViewById(R.id.text_status)
        devName = view.findViewById(R.id.device_name)
        devStPict = view.findViewById(R.id.device_st)
        devName?.text = device?.name
        devStatus?.text = "Подключение"
        db = DatabaseHelper(requireContext())
        gatt = device?.connectGatt(requireContext(), true, mCallback)
        return view
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    override fun onDestroy() {
        super.onDestroy()
        gatt?.close()
    }

    @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR2)
    private val mCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        // Invoked when Bluetooth connection changes
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            requireActivity().runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    devStatus?.text = "Подключено"
                    devStPict?.setImageResource(R.drawable.blt)
                    this@DeviceFragment.gatt?.discoverServices()
                    senderService?.start()
                } else {
                    devStatus?.text = "Отключено"
                    devStPict?.setImageResource(R.drawable.nosig)
                    senderService?.stop()
                    characteristics.forEach { (charName, characteristic) ->
                        characteristic.textView.text = ""
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Listen for Heart Rate notification
                deviceConfig.characteristics.map { (charName, config) ->
                    characteristics[charName]?.bluetoothGattService = gatt.getService(UUID.fromString(config.serviceUUID))
                        .also { service ->
                            characteristics[charName]?.bluetoothGattCharacteristic = service.getCharacteristic(UUID.fromString(config.characteristicUUID))
                                .also { characteristic ->
                                    gatt.setCharacteristicNotification(characteristic, true)
                                    characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                                        .let {
                                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                            gatt.writeDescriptor(it)
                                        }
                                }
                        }

                }
            }
        }

//        override fun onDescriptorWrite(
//            gatt: BluetoothGatt,
//            descriptor: BluetoothGattDescriptor,
//            status: Int
//        ) {
//            if (descriptor.characteristic.uuid == HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID) {
//                // Listen for Respiration Rate notification
//                val respSvc = gatt.getService(RESPIRATION_SERVICE_UUID)
//                val respChar = respSvc.getCharacteristic(
//                    RESPIRATION_RATE_MEASUREMENT_CHARACTERISTIC_UUID
//                )
//                gatt.setCharacteristicNotification(respChar, true)
//                val respDescriptor = respChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
//                respDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
//                gatt.writeDescriptor(respDescriptor)
//            } else if (descriptor.characteristic.uuid == RESPIRATION_RATE_MEASUREMENT_CHARACTERISTIC_UUID) {
//                //Listen for Accelerometer notification
//                val accSvc = gatt.getService(ACCELEROMETER_SERVICE_UUID)
//                val accChar = accSvc.getCharacteristic(
//                    ACCELEROMETER_MEASUREMENT_CHARACTERISTIC_UUID
//                )
//                gatt.setCharacteristicNotification(accChar, true)
//                val accDescriptor = accChar.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
//                accDescriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
//                gatt.writeDescriptor(accDescriptor)
//            }
//        }

        private fun changeHeartRateLabel(heartRateCharacteristic: BluetoothGattCharacteristic) {
            val data = heartRateCharacteristic.value
            val flag = data[0].toInt()
            val format: Int = if (flag and 0x01 == 0) {
                BluetoothGattCharacteristic.FORMAT_UINT8
            } else {
                BluetoothGattCharacteristic.FORMAT_UINT16
            }
            val heartRate = heartRateCharacteristic.getIntValue(format, 1)
            characteristics["heartRate"]?.textView?.text = heartRate.toString()
            characteristics["heartRate"]?.isUpdated = true
        }

        private fun changeRespirationRateLabel(respirationRateCharacteristic: BluetoothGattCharacteristic) {
            val data = respirationRateCharacteristic.value
            val flag = data[0]
            val format: Int = if (flag and 0x01 == 0.toByte()) {
                BluetoothGattCharacteristic.FORMAT_UINT8
            } else {
                BluetoothGattCharacteristic.FORMAT_UINT16
            }
            val respRate = respirationRateCharacteristic.getIntValue(format, 1)
            characteristics["respRate"]?.textView?.text = respRate.toString()
//            this@DeviceFragment.respRate!!.text = respRate.toString()
            val isInspExpPresent = flag and 0x02 != 0.toByte()
            inspRate?.text = ""
            expRate?.text = ""
            if (isInspExpPresent) {
                val startOffset =
                    1 + if (format == BluetoothGattCharacteristic.FORMAT_UINT8) 1 else 2
                var inspFirst = flag and 0x04 == 0.toByte()
                val inspStringBuilder = StringBuilder()
                val expStringBuilder = StringBuilder()
                var i = startOffset
                while (i < data.size) {
                    val value = respirationRateCharacteristic.getIntValue(
                        BluetoothGattCharacteristic.FORMAT_UINT16,
                        i
                    ) / 32.0f
                    inspFirst = if (inspFirst) {
                        inspStringBuilder.append(value)
                        false
                    } else {
                        expStringBuilder.append(value)
                        true
                    }
                    i += 2
                }
                characteristics["inspRate"]?.textView?.text = inspStringBuilder.toString()
                characteristics["expRate"]?.textView?.text = inspStringBuilder.toString()
//                inspRate!!.text = inspStringBuilder.toString()
//                expRate!!.text = expStringBuilder.toString()
            }
        }

        private fun changeAccelerometerLabel(accelerometerCharacteristic: BluetoothGattCharacteristic) {
            val data = accelerometerCharacteristic.value
            val flag = data[0]
            val format = BluetoothGattCharacteristic.FORMAT_UINT16
            var dataIndex = 1
            val isStepCountPresent = flag and 0x01 != 0.toByte()
            val isActivityPresent = flag and 0x02 != 0.toByte()
            val isCadencePresent = flag and 0x04 != 0.toByte()
            if (isStepCountPresent) {
                val stepCount = accelerometerCharacteristic.getIntValue(format, dataIndex)
                characteristics["stepsCount"]?.textView?.text = stepCount.toString()
//                stepsCount?.text = stepCount.toString()
                dataIndex += 2
            }
            if (isActivityPresent) {
                val activity = accelerometerCharacteristic.getIntValue(format, dataIndex) / 256.0f
//                this@DeviceFragment.activityRate!!.text = activity.toString()
                characteristics["activityRate"]?.textView?.text = activity.toString()
                dataIndex += 2
            }
            if (isCadencePresent) {
                val cadence = accelerometerCharacteristic.getIntValue(format, dataIndex)
                characteristics["cadence"]?.textView?.text = cadence.toString()
//                this@DeviceFragment.cadence!!.text = cadence.toString()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            requireActivity().runOnUiThread {
                deviceConfig.characteristics.filter { (charName, config) ->
                    UUID.fromString(config.characteristicUUID) == characteristic.uuid
                }
                    .map { it.key }
                    .first()
                    .let {
                        when(it) {
                            "heartRate" -> changeHeartRateLabel(characteristic)
                            else -> throw NotImplementedError("Not implemented yet")
                        }
                    }
//                when (characteristic.uuid) {
//                    HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID -> changeHeartRateLabel(characteristic)
//                    RESPIRATION_RATE_MEASUREMENT_CHARACTERISTIC_UUID -> changeRespirationRateLabel(characteristic)
//                    ACCELEROMETER_MEASUREMENT_CHARACTERISTIC_UUID -> changeAccelerometerLabel(characteristic)
//                }
                //Heart Rate Received
                val dfDateAndTime: DateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                val milliseconds: DateFormat = SimpleDateFormat("SSS", Locale.US)
                val now = Date()
                val myDate = dfDateAndTime.format(now)
                val millis = milliseconds.format(now)
                val result = JSONObject().apply {
                    put("Clitime", myDate)
                    put("Millisec", Integer.valueOf(millis))
                }
                characteristics.filter { (charName, characteristic) ->
                    characteristic.isUpdated
                }.forEach { (charName, characteristic) ->
                    result.put(charName, Integer.valueOf(characteristic.textView.text.toString()))
                }
//                try {
//                    var value = heartRate!!.text.toString()
//                    result.put(
//                        "HeartRate",
//                        if (value.isNotEmpty()) Integer.valueOf(value) else JSONObject.NULL
//                    )
//                    value = stepsCount!!.text.toString()
//                    result.put(
//                        "Steps",
//                        if (value.isNotEmpty()) Integer.valueOf(value) else JSONObject.NULL
//                    )
//                    value = activityRate!!.text.toString()
//                    result.put(
//                        "Activity",
//                        if (value.isNotEmpty()) java.lang.Float.valueOf(value) else JSONObject.NULL
//                    )
//                    value = cadence!!.text.toString()
//                    result.put(
//                        "Cadence",
//                        if (value.isNotEmpty()) Integer.valueOf(value) else JSONObject.NULL
//                    )
//                    result.put("Clitime", myDate)
//                    result.put("Millisec", Integer.valueOf(millis))
//                    value = respRate!!.text.toString()
//                    result.put(
//                        "RespRate",
//                        if (value.isNotEmpty()) Integer.valueOf(value) else JSONObject.NULL
//                    )
//                    value = inspRate!!.text.toString()
//                    result.put(
//                        "Insp",
//                        if (value.isNotEmpty()) java.lang.Float.valueOf(value) else JSONObject.NULL
//                    )
//                    value = expRate!!.text.toString()
//                    result.put(
//                        "Exp",
//                        if (value.isNotEmpty()) java.lang.Float.valueOf(value) else JSONObject.NULL
//                    )
//                } catch (ex: Exception) {
//                    ex.printStackTrace()
//                }
                Log.d(TAG, result.toString())
                Log.d(TAG, db!!.insertNote(result).toString())
                Log.d(TAG, db!!.notesCount.toString())
            }
        }
    }

    fun sendData(mqttAndroidClient: MqttAndroidClient, data: JSONObject) {
        val prefs = requireContext().getSharedPreferences(
            requireContext().getString(R.string.ACC_DATA),
            Context.MODE_PRIVATE
        )
        val jwt = prefs.getString("JWT", "")
        val userId = prefs.getString("UserId", "")
        val deviceId = prefs.getString("DeviceId", "")
        val options = MqttConnectOptions()
        options.userName = "username"
        options.password = jwt!!.toCharArray()
        try {
            mqttAndroidClient.connect(options, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    Log.d(TAG, "Connection Success!")
                    try {
                        val dataString = data.toString()
                        val message = MqttMessage(dataString.toByteArray())
                        Log.d(TAG, "Publishing message$message")
                        message.qos = 2
                        message.isRetained = false
                        mqttAndroidClient.publish("c/$userId/$deviceId/data", message)
                        //SenderService.this.dbhelper.deleteNote(ids.get(i));
                    } catch (ex: MqttException) {
                        ex.printStackTrace()
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken, exception: Throwable) {
                    Log.d(TAG, "Connection Failure!")
                    Log.d(TAG, exception.toString())
                }
            })
        } catch (ex: MqttException) {
            ex.printStackTrace()
        }
    }

    companion object {
        // Heart Rate Service UUID
        private val HEART_RATE_MEASUREMENT_SERVICE_UUID =
            UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")

        // Heart Rate Measurement UUID
        private val HEART_RATE_MEASUREMENT_CHARACTERISTIC_UUID =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

        // Respiration Rate Service UUID
        private val RESPIRATION_SERVICE_UUID =
            UUID.fromString("3b55c581-bc19-48f0-bd8c-b522796f8e24")

        // Respiration Rate Measurement UUID
        private val RESPIRATION_RATE_MEASUREMENT_CHARACTERISTIC_UUID =
            UUID.fromString("9bc730c3-8cc0-4d87-85bc-573d6304403c")

        // UUID for notification
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // Accelerometer Service UUID
        private val ACCELEROMETER_SERVICE_UUID =
            UUID.fromString("bdc750c7-2649-4fa8-abe8-fbf25038cda3")

        // Accelerometer Measurement UUID
        private val ACCELEROMETER_MEASUREMENT_CHARACTERISTIC_UUID =
            UUID.fromString("75246a26-237a-4863-aca6-09b639344f43")
        private val TAG = DeviceFragment::class.java.simpleName
    }
}