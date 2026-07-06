package pl.wojciechbaran.tomtomwatch

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.widget.Toast

class UsbReceiver : BroadcastReceiver() {
    private val ACTION_USB_PERMISSION = "pl.wojciechbaran.tomtomwatch.USB_PERMISSION"
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("UsbReceiver", "Action: " + action)
        if (ACTION_USB_PERMISSION == action) {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
//                putExtra(UsbManager.EXTRA_DEVICE, device)
            }
            context.startActivity(launchIntent)
        }
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action) {
            val device: UsbDevice? =
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

            if (device == null) {
                Log.e("UsbReceiver", "No device found in intent")
                Toast.makeText(context, "No device in intent", Toast.LENGTH_SHORT).show()
                return
            }

            Log.i("UsbReceiver", "USB device attached:")
            Log.i("UsbReceiver", "  Device name: ${device.deviceName}")
            Log.i("UsbReceiver", "  Vendor ID: ${device.vendorId}")
            Log.i("UsbReceiver", "  Product ID: ${device.productId}")

            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            var hasPermission = usbManager.hasPermission(device)
            Log.i("UsbReceiver", "hasPermission: " + hasPermission)
            if (!hasPermission) {
                val permissionIntent = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent("pl.wojciechbaran.tomtomwatch.USB_PERMISSION"),
                    PendingIntent.FLAG_IMMUTABLE
                )
                usbManager.requestPermission(device, permissionIntent);
                Log.i("UsbReceiver", "PermissionIntent: " + permissionIntent)
            }
            hasPermission = usbManager.hasPermission(device)
            if (hasPermission) {
                Log.i("UsbReceiver", "Permission granted automatically.")
                Toast.makeText(
                    context,
                    "USB Device auto-attached: ${device.deviceName}",
                    Toast.LENGTH_LONG
                ).show()

                // Optional: Launch MainActivity with the device info
                val launchIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(UsbManager.EXTRA_DEVICE, device)
                }
                context.startActivity(launchIntent)

            } else {
                Log.w("UsbReceiver", "Permission NOT granted.")
                Toast.makeText(context, "No permission for device", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
