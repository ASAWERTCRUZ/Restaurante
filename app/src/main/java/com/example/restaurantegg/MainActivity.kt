package com.example.restaurantegg

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageReference
import com.mapbox.geojson.Point
import com.mapbox.maps.MapView
import com.mapbox.maps.Style
import com.mapbox.maps.plugin.annotation.annotations
import com.mapbox.maps.plugin.annotation.generated.PointAnnotationOptions
import com.mapbox.maps.plugin.annotation.generated.createPointAnnotationManager
import com.mapbox.maps.plugin.gestures.addOnMapClickListener
import kotlin.math.*
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import android.widget.TextView
import java.io.ByteArrayOutputStream
import com.bumptech.glide.load.resource.bitmap.CircleCrop

class MainActivity : AppCompatActivity() {
    private var mapView: MapView? = null
    private var selectedImageBitmap: Bitmap? = null // Variable para almacenar la imagen seleccionada
    private lateinit var dishImageView: ImageView // Variable para la ImageView del plato
    private val markers = mutableMapOf<Point, MarkerInfo>() // Mapa para almacenar la información de los marcadores
    private val minDistance = 15.0// Distancia mínima de los marcadores
    private lateinit var firestore: FirebaseFirestore
    private lateinit var storage: FirebaseStorage

    companion object {
        private const val PICK_IMAGE_REQUEST = 1
    }

    data class MarkerInfo(
        val restaurantName: String,
        val dishName: String,
        val dishDescription: String,
        val imageUrl: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializar Firebase Firestore y Storage
        firestore = FirebaseFirestore.getInstance()
        storage = FirebaseStorage.getInstance()

        mapView = findViewById(R.id.mapView)
        mapView?.getMapboxMap()?.loadStyleUri(
            Style.MAPBOX_STREETS,
            object : Style.OnStyleLoaded {
                override fun onStyleLoaded(style: Style) {
                    setupMapClickListener()
                    agregarMarcadorEnMapaDesdeBaseDatos()
                }
            }
        )
    }

    private fun setupMapClickListener() {
        mapView?.getMapboxMap()?.addOnMapClickListener { point ->
            val nearestMarkerInfo = getNearestMarkerInfo(point)
            if (nearestMarkerInfo != null) {
                // Mostrar la información del marcador más cercano si el punto está demasiado cerca
                showMarkerInfoDialog(nearestMarkerInfo)
            } else {
                showAddMarkerDialog(point)
            }
            true
        }
    }

    private fun getNearestMarkerInfo(point: Point): MarkerInfo? {
        markers.keys.forEach {
            val distance = calculateDistance(it.latitude(), it.longitude(), point.latitude(), point.longitude())
            if (distance < minDistance) {
                return markers[it]
            }
        }
        return null
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // Radio de la tierra en metros
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    private fun showAddMarkerDialog(point: Point) {
        val builder = AlertDialog.Builder(this)
        val inflater: LayoutInflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_add_marker, null)

        val restaurantNameEditText = dialogView.findViewById<EditText>(R.id.edit_text_restaurant_name)
        val dishNameEditText = dialogView.findViewById<EditText>(R.id.edit_text_dish_name)
        val dishDescriptionEditText = dialogView.findViewById<EditText>(R.id.edit_text_dish_description)
        dishImageView = dialogView.findViewById(R.id.image_view_dish)

        dishImageView.setOnClickListener {
            // Abrir la galería para seleccionar una imagen
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, PICK_IMAGE_REQUEST)
        }

        builder.setView(dialogView)
            .setPositiveButton("Añadir") { dialog, _ ->
                val restaurantName = restaurantNameEditText.text.toString()
                val dishName = dishNameEditText.text.toString()
                val dishDescription = dishDescriptionEditText.text.toString()

                if (selectedImageBitmap != null) {
                    uploadImageToStorage(point, restaurantName, dishName, dishDescription, selectedImageBitmap!!)
                } else {
                    addMarker(point, restaurantName, dishName, dishDescription, null)
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancelar") { dialog, _ ->
                dialog.dismiss()
            }
        builder.create().show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            val imageUri: Uri? = data?.data
            imageUri?.let {
                selectedImageBitmap = uriToBitmap(it)
                dishImageView.setImageBitmap(selectedImageBitmap) // Mostrar la imagen seleccionada en la ImageView
            }
        }
    }

    private fun uriToBitmap(uri: Uri): Bitmap {
        return if (android.os.Build.VERSION.SDK_INT >= 29) {
            val source = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(contentResolver, uri)
        }
    }

    private fun uploadImageToStorage(point: Point, restaurantName: String, dishName: String, dishDescription: String, image: Bitmap) {
        val storageRef: StorageReference = storage.reference
        val imagesRef: StorageReference = storageRef.child("images/${System.currentTimeMillis()}.jpg")

        val baos = ByteArrayOutputStream()
        image.compress(Bitmap.CompressFormat.JPEG, 100, baos)
        val data = baos.toByteArray()

        val uploadTask = imagesRef.putBytes(data)
        uploadTask.addOnSuccessListener { taskSnapshot ->
            imagesRef.downloadUrl.addOnSuccessListener { uri ->
                val imageUrl = uri.toString()
                addMarker(point, restaurantName, dishName, dishDescription, imageUrl)
            }.addOnFailureListener { e ->
                showToast("Error al obtener la URL de la imagen: ${e.message}")
            }
        }.addOnFailureListener { e ->
            showToast("Error al subir la imagen: ${e.message}")
        }
    }
    private fun addMarker(point: Point, restaurantName: String, dishName: String, dishDescription: String, imageUrl: String?) {
        // Verificar que los datos no sean nulos o vacíos
        if (restaurantName.isEmpty()) {
            showToast("Error: falta el nombre del restaurante")
            return
        }
        if (dishName.isEmpty()) {
            showToast("Error: falta el nombre del plato")
            return
        }
        if (dishDescription.isEmpty()) {
            showToast("Error: falta la descripción del plato")
            return
        }
        // Crear un mapa para almacenar los datos del marcador
        val markerData = hashMapOf(
            "nombre_restaurante" to restaurantName,
            "nombre_plato" to dishName,
            "descripcion_plato" to dishDescription,
            "latitud" to point.latitude(),
            "longitud" to point.longitude(),
            "foto_restaurante" to imageUrl
        )

        // Guardar los datos del marcador en Firestore
        firestore.collection("restauranteG")
            .add(markerData)
            .addOnSuccessListener {
                showToast("Marcador añadido a Firestore")
                //colocque esto par aque se actualize
                agregarMarcadorEnMapaDesdeBaseDatos()
            }
            .addOnFailureListener { e ->
                showToast("Error al añadir marcador a Firestore: ${e.message}")
            }
    }

    private fun showMarkerInfoDialog(markerInfo: MarkerInfo) {
        val builder = AlertDialog.Builder(this)
        val inflater: LayoutInflater = LayoutInflater.from(this)
        val dialogView = inflater.inflate(R.layout.dialog_marker_info, null)

        dialogView.findViewById<TextView>(R.id.text_view_restaurant_name).text = markerInfo.restaurantName
        dialogView.findViewById<TextView>(R.id.text_view_dish_name).text = markerInfo.dishName
        dialogView.findViewById<TextView>(R.id.text_view_dish_description).text = markerInfo.dishDescription

        // Cargar la imagen del plato, si está disponible
        val dishImageView = dialogView.findViewById<ImageView>(R.id.image_view_dish)
        markerInfo.imageUrl?.let { url ->
            Glide.with(this)
                .load(url)
                .into(dishImageView)
        } ?: run {
            dishImageView.setImageResource(R.drawable.red_marker) // Imagen predeterminada si no hay URL
        }

        builder.setView(dialogView)
        builder.setPositiveButton("OK", null)
        builder.create().show()
    }

    private fun agregarMarcadorEnMapaDesdeBaseDatos() {
        firestore.collection("restauranteG")
            .get()
            .addOnSuccessListener { querySnapshot ->
                for (document in querySnapshot.documents) {
                    try {
                        val restaurantName = document.getString("nombre_restaurante") ?: ""
                        val dishName = document.getString("nombre_plato") ?: ""
                        val dishDescription = document.getString("descripcion_plato") ?: ""
                        val imageUrl = document.getString("foto_restaurante") // Obtener la URL de la imagen
                        // Intentar obtener latitud y longitud de varias formas
                        val latitude = getNumberValue(document, "latitud")
                        val longitude = getNumberValue(document, "longitud")

                        if (latitude != null && longitude != null) {
                            val point = Point.fromLngLat(longitude, latitude)
                            addMarkerToMap(point, restaurantName, dishName, dishDescription, imageUrl)
                        } else {
                            Log.w("MarcadorError", "Latitud o longitud inválida para el documento ${document.id}")
                        }
                    } catch (e: Exception) {
                        Log.e("MarcadorError", "Error al procesar documento ${document.id}: ${e.message}")
                    }
                }
            }
            .addOnFailureListener { e ->
                showToast("Error al recuperar los marcadores: ${e.message}")
            }
    }

    private fun getNumberValue(document: DocumentSnapshot, field: String): Double? {
        return when (val value = document.get(field)) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        }
    }
    private fun addMarkerToMap(point: Point, restaurantName: String, dishName: String, dishDescription: String, imageUrl: String?) {
        val annotationApi = mapView?.annotations
        val pointAnnotationManager = annotationApi?.createPointAnnotationManager()

        // Configurar las opciones iniciales para la anotación del marcador
        val markerOptions = PointAnnotationOptions()
            .withPoint(point)

        // Añadir la información del marcador al mapa
        markers[point] = MarkerInfo(restaurantName, dishName, dishDescription, imageUrl)

        // Cargar la imagen con Glide y configurarla como icono del marcador
        imageUrl?.let { url ->
            Glide.with(this)
                .asBitmap()
                .load(url)
                .transform(CircleCrop()) // Aplicar la transformación circular
                .override(150, 150) // Redimensionar la imagen a 100x100 píxeles
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        // Añadir la imagen como icono al marcador
                        markerOptions.withIconImage(bitmapToImage(resource))
                        markerOptions.withIconSize(1.0) // Mantener el tamaño de 50 píxeles
                        pointAnnotationManager?.create(markerOptions)
                    }

                    override fun onLoadCleared(placeholder: Drawable?) {
                        // Manejar el caso en que la carga se cancele
                    }
                })
        } ?: run {
            // Si no hay URL de imagen, usar un icono predeterminado
            markerOptions.withIconImage("default_marker_icon")
            pointAnnotationManager?.create(markerOptions)
        }
    }



    private fun bitmapToImage(bitmap: Bitmap): String {
        val style = mapView?.getMapboxMap()?.style
        val imageId = "custom-marker-${System.currentTimeMillis()}"

        style?.addImage(imageId, bitmap)
        return imageId
    }



    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
