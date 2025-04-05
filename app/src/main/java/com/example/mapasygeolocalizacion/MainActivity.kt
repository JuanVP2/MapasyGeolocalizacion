package com.example.deregresoacasa

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.location.Location
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager  // AndroidX PreferenceManager
import com.example.mapasygeolocalizacion.ApiService
import com.example.mapasygeolocalizacion.RouteResponse
import com.example.mapasygeolocalizacion.R
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.views.overlay.Polyline
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class MainActivity : ComponentActivity() {
    private val LOCATION_REQUEST_CODE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Configuración mínima de osmdroid: userAgentValue
        Configuration.getInstance().userAgentValue = packageName

        // Solicitar permiso de ubicación si no se ha concedido
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_REQUEST_CODE
            )
        }

        setContent {
            HomeRouteScreen()
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeRouteScreen() {
    val context = LocalContext.current

    // Cargar la configuración de osmdroid y establecer un userAgent válido
    LaunchedEffect(Unit) {
        val appContext = context.applicationContext
        // Cargar las preferencias de osmdroid
        Configuration.getInstance().load(
            appContext,
            PreferenceManager.getDefaultSharedPreferences(appContext)
        )
        // Establecer el userAgent con el packageName de la app
        Configuration.getInstance().userAgentValue = context.packageName
    }

    val permissionState = rememberPermissionState(permission = Manifest.permission.ACCESS_FINE_LOCATION)
    val osmMapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
        }
    }

    var currentLocation by remember { mutableStateOf<GeoPoint?>(null) }
    var homeLocation by remember { mutableStateOf<GeoPoint?>(null) }
    val tempMarkerState = remember { mutableStateOf<Marker?>(null) }
    val tappedPoint = remember { mutableStateOf<GeoPoint?>(null) }
    var routeOverlay: Polyline? by remember { mutableStateOf(null) }

    // Funciones para cargar y guardar la ubicación de casa
    fun loadHomeLocation(ctx: Context): GeoPoint? {
        val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val lat = prefs.getFloat("home_lat", 0f)
        val lon = prefs.getFloat("home_lon", 0f)
        return if (lat != 0f && lon != 0f) GeoPoint(lat.toDouble(), lon.toDouble()) else null
    }

    fun saveHomeLocation(ctx: Context, point: GeoPoint) {
        val prefs = ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("home_lat", point.latitude.toFloat())
            putFloat("home_lon", point.longitude.toFloat())
            apply()
        }
    }

    // Dibuja la ruta en el mapa usando la lista de coordenadas obtenida
    fun showRoute(map: MapView, coords: List<List<Double>>) {
        routeOverlay?.let { map.overlays.remove(it) }
        val polyline = Polyline().apply {
            setPoints(coords.map { coord -> GeoPoint(coord[1], coord[0]) })
            width = 5f
        }
        routeOverlay = polyline
        map.overlays.add(polyline)
        map.invalidate()
    }

    // Elimina la ruta actual
    fun removeRoute() {
        routeOverlay?.let {
            osmMapView.overlays.remove(it)
            routeOverlay = null
            osmMapView.invalidate()
        }
    }

    // Realiza la solicitud de ruta a OpenRouteService utilizando el método GET
    fun fetchRoute(start: String, destination: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val retrofit = getRetrofitInstance()
            val apiService = retrofit.create(ApiService::class.java)
            val response = apiService.getRoute(
                apiKey = "5b3ce3597851110001cf62489b271b9a431546f9ada0b5b49256aec3",
                start = start,
                end = destination
            )
            if (response.isSuccessful) {
                val routeData: RouteResponse? = response.body()
                val routeCoords = routeData?.features?.firstOrNull()?.geometry?.coordinates ?: emptyList()
                withContext(Dispatchers.Main) {
                    showRoute(osmMapView, routeCoords)
                }
            } else {
                Log.e("RouteFetch", "Error al obtener la ruta: ${response.errorBody()?.string()}")
            }
        }
    }

    // Solicita la ubicación si el permiso ha sido otorgado
    LaunchedEffect(permissionState.status) {
        if (permissionState.status.isGranted) {
            obtainLocation(context) { loc ->
                currentLocation = GeoPoint(loc.latitude, loc.longitude)
                osmMapView.controller.apply {
                    setZoom(15.0)
                    setCenter(currentLocation)
                }

                // Configurar marcador para la ubicación actual
                val currentDrawable = ContextCompat.getDrawable(context, R.drawable.ubicacion)
                val currentBmp = (currentDrawable as BitmapDrawable).bitmap
                val currentIcon = BitmapDrawable(context.resources, Bitmap.createScaledBitmap(currentBmp, 20, 20, false))
                val currMarker = Marker(osmMapView).apply {
                    position = currentLocation
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    icon = currentIcon
                    title = "Ubicación actual"
                }
                osmMapView.overlays.add(currMarker)

                // Cargar y mostrar marcador de casa si existe
                homeLocation = loadHomeLocation(context)
                homeLocation?.let { point ->
                    val homeDrawable = ContextCompat.getDrawable(context, R.drawable.casa)
                    val homeBmp = (homeDrawable as BitmapDrawable).bitmap
                    val homeIcon = BitmapDrawable(context.resources, Bitmap.createScaledBitmap(homeBmp, 20, 20, false))
                    val homeMarker = Marker(osmMapView).apply {
                        position = point
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        icon = homeIcon
                        title = "Mi casa"
                    }
                    osmMapView.overlays.add(homeMarker)
                }

                // Agregar overlay para detectar toques en el mapa
                val touchReceiver = object : MapEventsReceiver {
                    override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean {
                        p?.let { point ->
                            tempMarkerState.value?.let { marker -> osmMapView.overlays.remove(marker) }
                            val touchMarker = Marker(osmMapView).apply {
                                position = point
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                icon = currentIcon
                                title = "Marcador temporal"
                            }
                            tempMarkerState.value = touchMarker
                            tappedPoint.value = point
                            osmMapView.overlays.add(touchMarker)
                            osmMapView.invalidate()
                        }
                        return true
                    }
                    override fun longPressHelper(p: GeoPoint?) = false
                }
                val touchOverlay = MapEventsOverlay(touchReceiver)
                osmMapView.overlays.add(touchOverlay)
                osmMapView.invalidate()

                // Solicitar la ruta desde la ubicación actual hacia la ubicación de casa (si se cargó)
                currentLocation?.let { curr ->
                    val startStr = "${curr.longitude},${curr.latitude}"
                    val destStr = "${homeLocation?.longitude},${homeLocation?.latitude}"
                    fetchRoute(startStr, destStr)
                }
            }
        }
    }

    // Interfaz de usuario
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(factory = { osmMapView }, modifier = Modifier.fillMaxSize())
            Button(
                onClick = { currentLocation?.let { osmMapView.controller.setCenter(it) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
            ) {
                Text("Centrar en mi ubicación")
            }
            Button(
                onClick = {
                    removeRoute()
                    tappedPoint.value?.let { newHome ->
                        // Eliminar el marcador de "Mi casa" anterior y cualquier "Marcador temporal"
                        osmMapView.overlays.removeAll { overlay ->
                            overlay is Marker && (
                                    overlay.title == "Mi casa" || overlay.title == "Marcador temporal"
                                    )
                        }

                        // Ahora creas el nuevo marcador de "Mi casa"
                        val casaDrawable = ContextCompat.getDrawable(context, R.drawable.casa)
                        val casaBmp = (casaDrawable as BitmapDrawable).bitmap
                        val casaIcon = BitmapDrawable(context.resources, Bitmap.createScaledBitmap(casaBmp, 20, 20, false))
                        saveHomeLocation(context, newHome)
                        val casaMarker = Marker(osmMapView).apply {
                            position = newHome
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            icon = casaIcon
                            title = "Mi casa"
                        }
                        osmMapView.overlays.add(casaMarker)
                        osmMapView.invalidate()

                        // Solicitar la ruta hacia la nueva ubicación de casa
                        currentLocation?.let { curr ->
                            val startStr = "${curr.longitude},${curr.latitude}"
                            val endStr = "${newHome.longitude},${newHome.latitude}"
                            fetchRoute(startStr, endStr)
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter).padding(70.dp)
            ) {
                Text("Establecer casa")
            }

        }
    }
}

@SuppressLint("MissingPermission")
fun obtainLocation(context: Context, onLocation: (Location) -> Unit) {
    val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
    fusedLocationClient.lastLocation
        .addOnSuccessListener { location ->
            location?.let { onLocation(it) }
        }
        .addOnFailureListener { e ->
            Log.e("LocationError", "Error al obtener ubicación", e)
        }
}

private fun getRetrofitInstance(): Retrofit {
    return Retrofit.Builder()
        .baseUrl("https://api.openrouteservice.org/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()
}
