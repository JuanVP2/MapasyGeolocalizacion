package com.example.mapasygeolocalizacion

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.preference.PreferenceManager
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
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale

class MainActivity : ComponentActivity() {
    private val locatioRequestCode = 1001

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
                locatioRequestCode
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
            outlinePaint.strokeWidth = 5f
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
                val currentIcon = currentBmp.scale(20, 20, false)
                    .toDrawable(context.resources)
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
                    val homeIcon = homeBmp.scale(20, 20, false)
                        .toDrawable(context.resources)
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

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(
                    onClick = { currentLocation?.let { osmMapView.controller.setCenter(it) } },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Centrar mi ubicación")
                }
                Spacer(modifier = Modifier.width(16.dp))
                Button(
                    onClick = {
                        removeRoute()
                        tappedPoint.value?.let { newHome ->
                            osmMapView.overlays.removeAll { overlay ->
                                overlay is Marker && overlay.title == "Mi casa"
                            }
                            val casaDrawable = ContextCompat.getDrawable(context, R.drawable.casa)
                            val casaBmp = (casaDrawable as BitmapDrawable).bitmap
                            val casaIcon = casaBmp.scale(20, 20, false)
                                .toDrawable(context.resources)
                            saveHomeLocation(context, newHome)
                            val casaMarker = Marker(osmMapView).apply {
                                position = newHome
                                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                                icon = casaIcon
                                title = "Mi casa"
                            }
                            osmMapView.overlays.add(casaMarker)
                            osmMapView.invalidate()

                            currentLocation?.let { curr ->
                                val startStr = "${curr.longitude},${curr.latitude}"
                                val endStr = "${newHome.longitude},${newHome.latitude}"
                                fetchRoute(startStr, endStr)
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cambiar a casa")
                }
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
