package com.test.android.facerecognition

import android.Manifest
import android.app.Dialog
import android.app.Fragment
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.Typeface
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Surface
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.test.android.facerecognition.drawing.BorderedText
import com.test.android.facerecognition.drawing.MultiBoxTracker
import com.test.android.facerecognition.drawing.OverlayView
import com.test.android.facerecognition.face_recognition.FaceClassifier
import com.test.android.facerecognition.face_recognition.FaceClassifier.Recognition
import com.test.android.facerecognition.face_recognition.TFLiteFaceRecognition
import com.test.android.facerecognition.livefeed.CameraConnectionFragment
import com.test.android.facerecognition.livefeed.ImageUtils
import java.io.IOException


class MainActivity : AppCompatActivity(), ImageReader.OnImageAvailableListener {

    val PERMISSION_CODE = 121
    var handler: Handler? = null
    private var frameToCropTransform: Matrix? = null
    private var sensorOrientation = 0
    private var cropToFrameTransform: Matrix? = null

    private val MAINTAIN_ASPECT = false
    private val TEXT_SIZE_DIP = 10f
    var trackingOverlay: OverlayView? = null
    private var borderedText: BorderedText? = null
    private var tracker: MultiBoxTracker? = null
    private var useFacing: Int? = null
    private val KEY_USE_FACING = "use_facing"
    private val CROP_SIZE = 1000
    private val TF_OD_API_INPUT_SIZE2 = 160

    //TODO declare face detector
    lateinit var detector: FaceDetector

//    //TODO declare face recognizer
    lateinit var faceClassifier: FaceClassifier

    var registerFace = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //TODO handling permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED || checkSelfPermission(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                val permission = arrayOf<String>(
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                requestPermissions(permission, PERMISSION_CODE)
            }
        }
        val intent = intent
        useFacing = intent.getIntExtra(KEY_USE_FACING, CameraCharacteristics.LENS_FACING_BACK)

        //TODO show live camera footage
        setFragment();
        //TODO initialize the tracker to draw rectangles
        tracker = MultiBoxTracker(this)


        //TODO initalize face detector
        // Multiple object detection in static images
        val highAccuracyOpts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        detector = FaceDetection.getClient(highAccuracyOpts)


        //TODO initialize FACE Recognition
        try {
        faceClassifier = TFLiteFaceRecognition.create(assets, "facenet.tflite", TF_OD_API_INPUT_SIZE2, false, applicationContext)
        } catch (e: IOException) {
            e.printStackTrace();
            Toast.makeText(applicationContext, "Classifier could not be initialized", Toast.LENGTH_SHORT).show()
            finish();
        }

        findViewById<View>(R.id.imageView4).setOnClickListener { registerFace = true }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String?>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            setFragment()
        }
    }

    //TODO fragment which show llive footage from camera
    var previewHeight = 0
    var previewWidth = 0
    protected fun setFragment() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var cameraId: String? = null
        try {
            cameraId = manager.getCameraIdList()[useFacing ?: 0]
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        val fragment: Fragment
        val camera2Fragment = CameraConnectionFragment.newInstance(
            { size, rotation ->
                previewHeight = size!!.height
                previewWidth = size.width
                val textSizePx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, resources.displayMetrics)
                borderedText =
                    BorderedText(
                        textSizePx
                    )
                borderedText!!.setTypeface(Typeface.MONOSPACE)

                val cropSize = CROP_SIZE
                previewWidth = size.width
                previewHeight = size.height
                sensorOrientation = rotation - getScreenOrientation()

                rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888)
                croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)

                frameToCropTransform = ImageUtils.getTransformationMatrix(
                    previewWidth, previewHeight,
                    cropSize, cropSize,
                    sensorOrientation, MAINTAIN_ASPECT
                )
                cropToFrameTransform = Matrix()
                frameToCropTransform!!.invert(cropToFrameTransform)

                trackingOverlay = findViewById<View>(R.id.tracking_overlay) as OverlayView
                trackingOverlay!!.addCallback(
                    object : OverlayView.DrawCallback {
                        override fun drawCallback(canvas: Canvas?) {
                            tracker!!.draw(canvas!!)
                        }
                    })
                tracker!!.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation)
            },
            this,
            R.layout.camera_fragment,
            Size(640, 480)
        )
        camera2Fragment.setCamera(cameraId)
        fragment = camera2Fragment
        fragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
    }

    //TODO getting frames of live camera footage and passing them to model
    private var isProcessingFrame = false
    private val yuvBytes = arrayOfNulls<ByteArray>(3)
    private var rgbBytes: IntArray? = null
    private var yRowStride = 0
    private var postInferenceCallback: Runnable? = null
    private var imageConverter: Runnable? = null
    private var rgbFrameBitmap: Bitmap? = null
    private var croppedBitmap: Bitmap? = null
    override fun onImageAvailable(reader: ImageReader) {
        if (previewWidth == 0 || previewHeight == 0) {
            return
        }
        if (rgbBytes == null) {
            rgbBytes = IntArray(previewWidth * previewHeight)
        }
        try {
            val image = reader.acquireLatestImage() ?: return
            if (isProcessingFrame) {
                image.close()
                return
            }
            isProcessingFrame = true
            val planes = image.planes
            fillBytes(planes, yuvBytes)
            yRowStride = planes[0].rowStride
            val uvRowStride = planes[1].rowStride
            val uvPixelStride = planes[1].pixelStride
            imageConverter = Runnable {
                ImageUtils.convertYUV420ToARGB8888(
                    yuvBytes[0]!!,
                    yuvBytes[1]!!,
                    yuvBytes[2]!!,
                    previewWidth,
                    previewHeight,
                    yRowStride,
                    uvRowStride,
                    uvPixelStride,
                    rgbBytes!!
                )
            }
            postInferenceCallback = Runnable {
                image.close()
                isProcessingFrame = false
            }
            performFaceDetection()

        } catch (e: Exception) {
            Log.d("tryError", " ${e.message}")
            return
        }
    }

    protected fun fillBytes(planes: Array<Image.Plane>, yuvBytes: Array<ByteArray?>) {
        // Because of the variable row stride it's not possible to know in advance the actual necessary dimensions of the yuv planes.
        for (i in planes.indices) {
            val buffer = planes[i].buffer
            if (yuvBytes[i] == null) {
                yuvBytes[i] = ByteArray(buffer.capacity())
            }
            buffer[yuvBytes[i]]
        }
    }

    private fun getScreenOrientation(): Int {
        return when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_270 -> 270
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_90 -> 90
            else -> 0
        }
    }

    var mappedRecognitions: MutableList<Recognition>? = null
    //TODO perform face detection
    private fun performFaceDetection(){
        imageConverter!!.run()
        rgbFrameBitmap!!.setPixels(rgbBytes!!, 0, previewWidth, 0, 0, previewWidth, previewHeight)

        val canvas = Canvas(croppedBitmap!!)
        canvas.drawBitmap(rgbFrameBitmap!!, frameToCropTransform!!, null)

        Handler().post {
            mappedRecognitions = ArrayList<Recognition>()
            val image = InputImage.fromBitmap(croppedBitmap!!, 0)
            detector.process(image)
                .addOnSuccessListener { faces ->
                    for (face in faces) {
                        val bounds = face.boundingBox
                        performFaceRecognition(face, croppedBitmap!!)
                    }
                    registerFace = false
                    tracker!!.trackResults(mappedRecognitions, 10)
                    trackingOverlay!!.postInvalidate()
                    postInferenceCallback!!.run()
                }
                .addOnFailureListener {
                    // Task failed with an exception
                }
        }
    }

    //TODO perform face recognition
    private fun performFaceRecognition(face: Face, input: Bitmap){
        val bounds = face.boundingBox
        if(bounds.top < 0)
            bounds.top = 0
        if(bounds.left < 0)
            bounds.left = 0
        if(bounds.left + bounds.width() > input.width)
            bounds.right = input.width-1
        if(bounds.top + bounds.height() > input.height)
            bounds.bottom = input.height-1

        var crop = Bitmap.createBitmap(input,
            bounds.left,
            bounds.top,
            bounds.width(),
            bounds.height()-30)

        crop = Bitmap.createScaledBitmap(crop, TF_OD_API_INPUT_SIZE2, TF_OD_API_INPUT_SIZE2, false)
        val result = faceClassifier.recognizeImage(crop, registerFace)
        var title: String? = "Unknown"
        var confidence = 0f
        if (result != null) {
            if (registerFace) {
                registerFaceDialogue(crop, result)
            } else {
                if (result.distance < 0.75f) {
                    confidence = result.distance
                    title = result.title
                }
            }
        }

        val location = RectF(bounds)
        if (bounds != null) {
            if (useFacing == CameraCharacteristics.LENS_FACING_BACK) {
                location.right = input.getWidth() - location.right
                location.left = input.getWidth() - location.left
            }
            cropToFrameTransform!!.mapRect(location)
            val recognition = Recognition("${face.trackingId}", title, confidence, location)
            mappedRecognitions?.add(recognition)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    //TODO register face dialogue
    private fun registerFaceDialogue(croppedFace: Bitmap, rec: Recognition) {
        val dialog = Dialog(this)
        dialog.setContentView(R.layout.register_face_dialogue)
        val ivFace = dialog.findViewById<ImageView>(R.id.dlg_image)
        val nameEd = dialog.findViewById<EditText>(R.id.dlg_input)
        val register = dialog.findViewById<Button>(R.id.button2)
        ivFace.setImageBitmap(croppedFace)
        register.setOnClickListener(View.OnClickListener {
            val name = nameEd.text.toString()
            if (name.isEmpty()) {
                nameEd.error = "Enter Name"
                return@OnClickListener
            }
            faceClassifier.register(name, rec)
            Toast.makeText(this@MainActivity, "Face Registered Successfully", Toast.LENGTH_SHORT)
                .show()
            dialog.dismiss()
        })
        dialog.show()
    }
}