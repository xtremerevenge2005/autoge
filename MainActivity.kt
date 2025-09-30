package com.example.gestaoveiculos

import android.app.Activity
import android.app.Dialog
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PART_IMAGE_PICK     = 1001
        private const val REQUEST_VEHICLE_IMAGE_PICK = 1002
        private const val REQUEST_EXPORT_FILE        = 2001
        private const val REQUEST_IMPORT_FILE        = 2002
    }

    // Em memória
    private val vehicles = mutableListOf<Vehicle>()
    private val parts    = mutableListOf<Part>()
    private var currentVehicleId: Long = -1
    private var currentPartId:    Long = -1

    // Mapas de imagens
    private val partImagesMap    = mutableMapOf<Long, MutableList<Uri>>()
    private val vehicleImagesMap = mutableMapOf<Long, MutableList<Uri>>()

    // Adapters
    private lateinit var adapterVehicles:   ArrayAdapter<String>
    private lateinit var adapterAlerts:     ArrayAdapter<String>
    private lateinit var adapterCategories: ArrayAdapter<String>

    // DB, prefs & UI
    private lateinit var dbHelper: VehicleDbHelper
    private lateinit var prefs:    SharedPreferences
    private lateinit var tabHost: TabHost

    // ExpandableListView
    private lateinit var expandableListView: ExpandableListView
    private val categoryList = mutableListOf<String>()
    private val childrenList = mutableListOf<List<String>>()

    // Alertas
    private var defaultThreshold = 30
    private var viewModeByPiece = false
    private val collapsedPieces = mutableSetOf<Pair<String, String>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        // Edge‑to‑edge insets
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(sb.left, sb.top, sb.right, sb.bottom)
            insets
        }

        dbHelper = VehicleDbHelper(this)
        prefs    = getSharedPreferences("settings", MODE_PRIVATE)
        defaultThreshold = prefs.getInt("default_threshold", 30)

        setupTabHost()
        setupVehiclesTab()
        setupAlertsTab()

        if (prefs.getBoolean("show_alerts", true)) {
            showAlertsToasts()
        }
    }

    override fun onBackPressed() {
        if (tabHost.currentTabTag == "alerts") {
            tabHost.setCurrentTabByTag("vehicles")
            return
        }

        if (findViewById<ScrollView>(R.id.part_detail_view).visibility == View.VISIBLE) {
            findViewById<ScrollView>(R.id.part_detail_view).visibility = View.GONE
            findViewById<ScrollView>(R.id.detail_view).visibility      = View.VISIBLE
            return
        }
        if (findViewById<ScrollView>(R.id.detail_view).visibility == View.VISIBLE) {
            findViewById<ScrollView>(R.id.detail_view).visibility    = View.GONE
            findViewById<LinearLayout>(R.id.section_list).visibility = View.VISIBLE
            return
        }
        super.onBackPressed()
    }


    /*** Abas ***/
    private fun setupTabHost() {
        tabHost = findViewById<TabHost>(android.R.id.tabhost).apply { setup() }
        tabHost.addTab(tabHost.newTabSpec("vehicles")
            .setIndicator("Veículos")
            .setContent(R.id.tab_vehicles))
        tabHost.addTab(tabHost.newTabSpec("alerts")
            .setIndicator("Alertas")
            .setContent(R.id.tab_alerts))
    }

    private fun showDetail(v: Vehicle) {
        currentVehicleId = v.id
        findViewById<TextView>(R.id.tvModeloDetail).text = "Modelo: ${v.modelo}"
        findViewById<TextView>(R.id.tvMarcaDetail).text  = "Marca: ${v.marca}"
        findViewById<TextView>(R.id.tvAnoDetail).text    = "Ano: ${v.ano}"
        findViewById<TextView>(R.id.tvInfoDetail).text   = if (v.info.isNotEmpty()) "Info: ${v.info}" else "Info: —"

        loadImagesForVehicle(v.id)
        val vehImgContainer = findViewById<LinearLayout>(R.id.vehicleImagesLayout)
        vehImgContainer.removeAllViews()
        vehicleImagesMap[v.id]?.forEach { uri ->
            vehImgContainer.addView(createVehicleImageView(uri))
        }

        findViewById<Button>(R.id.buttonAddVehicleImage).setOnClickListener {
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                }
                .also { startActivityForResult(it, REQUEST_VEHICLE_IMAGE_PICK) }
        }

        findViewById<LinearLayout>(R.id.section_list).visibility = View.GONE
        findViewById<ScrollView>(R.id.detail_view).visibility    = View.VISIBLE
        loadPartsForVehicle(v.id)
    }

    /*** Aba Veículos ***/
    private fun setupVehiclesTab() {
        findViewById<Button>(R.id.buttonExport).setOnClickListener { doExport() }
        findViewById<Button>(R.id.buttonImport).setOnClickListener { doImport() }

        adapterVehicles = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        findViewById<ListView>(R.id.listViewVehicles).apply {
            adapter = adapterVehicles
            setOnItemClickListener { _, _, pos, _ -> showDetail(vehicles[pos]) }
            setOnItemLongClickListener { _, _, pos, _ ->
                vehicleContextMenu(vehicles[pos])
                true
            }
        }

        loadVehiclesFromDb()

        findViewById<Button>(R.id.buttonSave).setOnClickListener {
            val modelo = findViewById<EditText>(R.id.editTextModelo).text.toString().trim()
            val marca  = findViewById<EditText>(R.id.editTextMarca).text.toString().trim()
            val anoStr = findViewById<EditText>(R.id.editTextAno).text.toString().trim()
            val info   = findViewById<EditText>(R.id.editTextInfo).text.toString().trim()
            if (modelo.isEmpty() || marca.isEmpty() || anoStr.isEmpty()) {
                Toast.makeText(this, "Preencha Modelo, Marca e Ano.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ano = anoStr.toIntOrNull() ?: run {
                Toast.makeText(this, "Ano inválido.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            dbHelper.insertVehicle(modelo, marca, ano, info)
            loadVehiclesFromDb()
            clearVehicleForm()
            Toast.makeText(this, "Veículo salvo!", Toast.LENGTH_SHORT).show()
        }

        adapterCategories = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf())
        findViewById<AutoCompleteTextView>(R.id.editTextCategory).setAdapter(adapterCategories)
        updateCategorySuggestions()

        expandableListView = findViewById(R.id.expandableListViewParts)
        expandableListView.setOnTouchListener { v, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN || ev.action == MotionEvent.ACTION_MOVE) {
                v.parent.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        expandableListView.setOnChildClickListener { _, _, groupPos, childPos, _ ->
            val line = childrenList[groupPos][childPos]
            if (viewModeByPiece && line.startsWith("— ") && line.endsWith(" —")) {
                val pieceName = line.removePrefix("— ").removeSuffix(" —")
                val key = categoryList[groupPos] to pieceName
                if (!collapsedPieces.remove(key)) collapsedPieces.add(key)
                loadPartsForVehicle(currentVehicleId)
                expandableListView.expandGroup(groupPos)
                true
            } else {
                parts.find { formatPartLine(it) == line }?.let { showPartDetail(it) }
                true
            }
        }
        expandableListView.setOnItemLongClickListener { _, _, flatPos, _ ->
            val packed = expandableListView.getExpandableListPosition(flatPos)
            val g = ExpandableListView.getPackedPositionGroup(packed)
            val c = ExpandableListView.getPackedPositionChild(packed)
            if (c >= 0) {
                val line = childrenList[g][c]
                if (!line.startsWith("— ")) {
                    parts.find { formatPartLine(it) == line }?.let { partContextMenu(it) }
                    true
                } else false
            } else false
        }

        // ALTERAÇÃO: Instancia e aplica o TextWatcher corrigido.
        val dateField = findViewById<EditText>(R.id.editTextChangeDate)
        dateField.addTextChangedListener(DateTextWatcher(dateField))

        findViewById<Button>(R.id.buttonSavePart).setOnClickListener { savePart() }
        findViewById<Button>(R.id.buttonBack).setOnClickListener {
            findViewById<ScrollView>(R.id.detail_view).visibility    = View.GONE
            findViewById<LinearLayout>(R.id.section_list).visibility = View.VISIBLE
        }

        findViewById<RadioGroup>(R.id.radioGroupViewMode)
            .setOnCheckedChangeListener { _, checkedId ->
                viewModeByPiece = (checkedId == R.id.radioByPiece)
                if (currentVehicleId >= 0) loadPartsForVehicle(currentVehicleId)
            }
    }

    /*** Aba Alertas ***/
    private fun setupAlertsTab() {
        findViewById<EditText>(R.id.editTextDefaultThreshold)
            .setText(defaultThreshold.toString())
        findViewById<Button>(R.id.buttonSaveDefaultThreshold).setOnClickListener {
            val v = findViewById<EditText>(R.id.editTextDefaultThreshold)
                .text.toString().toIntOrNull()
            if (v != null && v > 0) {
                defaultThreshold = v
                prefs.edit().putInt("default_threshold", v).apply()
                Toast.makeText(this, "Threshold salvo: $v dias", Toast.LENGTH_SHORT).show()
                loadAlerts()
            } else {
                Toast.makeText(this, "Informe número válido", Toast.LENGTH_SHORT).show()
            }
        }

        adapterAlerts = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        findViewById<ListView>(R.id.listViewAlerts).adapter = adapterAlerts
        findViewById<Switch>(R.id.switchAlerts).apply {
            isChecked = prefs.getBoolean("show_alerts", true)
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean("show_alerts", isChecked).apply()
            }
        }
        loadAlerts()
    }

    // Código de Export/Import, onActivityResult, etc. permanece igual
    // ...
    // ...
    private fun doExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "gestaoveiculos_export.json")
        }
        startActivityForResult(intent, REQUEST_EXPORT_FILE)
    }

    private fun doImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        startActivityForResult(intent, REQUEST_IMPORT_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK || data?.data == null) return
        val uri = data.data!!

        when (requestCode) {
            REQUEST_PART_IMAGE_PICK -> {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val list = partImagesMap.getOrPut(currentPartId) { mutableListOf() }
                list.add(uri)
                saveImagesForPart(currentPartId)
                findViewById<LinearLayout>(R.id.partImagesLayout)
                    .addView(createPartImageView(uri))
            }
            REQUEST_VEHICLE_IMAGE_PICK -> {
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                val list = vehicleImagesMap.getOrPut(currentVehicleId) { mutableListOf() }
                list.add(uri)
                saveImagesForVehicle(currentVehicleId)
                findViewById<LinearLayout>(R.id.vehicleImagesLayout)
                    .addView(createVehicleImageView(uri))
            }
            REQUEST_EXPORT_FILE -> exportToUri(uri)
            REQUEST_IMPORT_FILE -> importFromUri(uri)
        }
    }

    private fun exportToUri(uri: Uri) {
        // ... (código sem alterações)
        val root = JSONObject()
        val vehiclesArr = JSONArray()

        val cVeh = dbHelper.readableDatabase.query(
            VehicleEntry.TABLE_NAME,
            arrayOf(
                VehicleEntry._ID,
                VehicleEntry.COL_MODELO,
                VehicleEntry.COL_MARCA,
                VehicleEntry.COL_ANO,
                VehicleEntry.COL_INFO
            ), null, null, null, null, null
        )
        while (cVeh.moveToNext()) {
            val vid    = cVeh.getLong(cVeh.getColumnIndexOrThrow(VehicleEntry._ID))
            val modelo = cVeh.getString(cVeh.getColumnIndexOrThrow(VehicleEntry.COL_MODELO))
            val marca  = cVeh.getString(cVeh.getColumnIndexOrThrow(VehicleEntry.COL_MARCA))
            val ano    = cVeh.getInt(cVeh.getColumnIndexOrThrow(VehicleEntry.COL_ANO))
            val info   = cVeh.getString(cVeh.getColumnIndexOrThrow(VehicleEntry.COL_INFO))
            val vJson = JSONObject().apply {
                put("modelo", modelo)
                put("marca",  marca)
                put("ano",    ano)
                put("info",   info)
                // imagens veículo
                val imgVehArr = JSONArray()
                (vehicleImagesMap[vid] ?: emptyList()).forEach { imgVehArr.put(it.toString()) }
                put("images_vehicle", imgVehArr)
                // peças
                val partsArr = JSONArray()
                val cPar = dbHelper.readableDatabase.query(
                    PartEntry.TABLE_NAME,
                    arrayOf(
                        PartEntry._ID,
                        PartEntry.COL_CATEGORY,
                        PartEntry.COL_NF_NUMBER,
                        PartEntry.COL_PIECE_NAME,
                        PartEntry.COL_MANUFACTURER,
                        PartEntry.COL_CHANGE_DATE,
                        PartEntry.COL_INTERVAL_DAYS,
                        PartEntry.COL_FUTURE_DATE,
                        PartEntry.COL_INFO,
                        PartEntry.COL_ALERT_THRESHOLD,
                        PartEntry.COL_VALUE
                    ),
                    "${PartEntry.COL_VEHICLE_ID}=?",
                    arrayOf(vid.toString()),
                    null, null, null
                )
                while (cPar.moveToNext()) {
                    val pid          = cPar.getLong(cPar.getColumnIndexOrThrow(PartEntry._ID))
                    val category     = cPar.getString(cPar.getColumnIndexOrThrow(PartEntry.COL_CATEGORY))
                    val nfNumber     = cPar.getString(cPar.getColumnIndexOrThrow(PartEntry.COL_NF_NUMBER))
                    val pieceName    = cPar.getString(cPar.getColumnIndexOrThrow(PartEntry.COL_PIECE_NAME))
                    val manufacturer = cPar.getString(cPar.getColumnIndexOrThrow(PartEntry.COL_MANUFACTURER))
                    val changeDate   = cPar.getString(cPar.getColumnIndexOrThrow(PartEntry.COL_CHANGE_DATE))
                    val intervalDays = cPar.getInt(cPar.getColumnIndexOrThrow(PartEntry.COL_INTERVAL_DAYS))
                    val futureDate   = cPar.getString(cPar.getColumnIndexOrThrow(PartEntry.COL_FUTURE_DATE))
                    val infoPart     = cPar.getString(cPar.getColumnIndexOrThrow(PartEntry.COL_INFO))
                    val alertThresh  = cPar.getInt(cPar.getColumnIndexOrThrow(PartEntry.COL_ALERT_THRESHOLD))
                    val value        = cPar.getDouble(cPar.getColumnIndexOrThrow(PartEntry.COL_VALUE))
                    val pJson = JSONObject().apply {
                        put("category",        category)
                        put("nf_number",       nfNumber)
                        put("piece_name",      pieceName)
                        put("manufacturer",    manufacturer)
                        put("change_date",     changeDate)
                        put("interval_days",   intervalDays)
                        put("future_date",     futureDate)
                        put("info",            infoPart)
                        put("alert_threshold", alertThresh)
                        put("value",           value)
                        // imagens peça
                        val imgArr = JSONArray()
                        (partImagesMap[pid] ?: emptyList()).forEach { imgArr.put(it.toString()) }
                        put("images_part", imgArr)
                    }
                    partsArr.put(pJson)
                }
                cPar.close()
                put("parts", partsArr)
            }
            vehiclesArr.put(vJson)
        }
        cVeh.close()

        root.put("vehicles", vehiclesArr)

        contentResolver.openOutputStream(uri)?.use { os ->
            OutputStreamWriter(os).use { it.write(root.toString(2)) }
        }
        Toast.makeText(this, "Exportação concluída!", Toast.LENGTH_SHORT).show()
    }

    private fun importFromUri(uri: Uri) {
        // ... (código sem alterações)
        val text = StringBuilder()
        contentResolver.openInputStream(uri)?.use { ins ->
            BufferedReader(InputStreamReader(ins)).use { br ->
                var line: String?
                while (br.readLine().also { line = it } != null) {
                    text.append(line)
                }
            }
        }
        try {
            val root = JSONObject(text.toString())
            val vehArr = root.getJSONArray("vehicles")

            // limpa banco
            dbHelper.writableDatabase.apply {
                delete(PartEntry.TABLE_NAME, null, null)
                delete(VehicleEntry.TABLE_NAME, null, null)
            }

            // limpa prefs de imagens
            val editor = prefs.edit()
            prefs.all.keys
                .filter { it.startsWith("images_part_") || it.startsWith("images_vehicle_") }
                .forEach { editor.remove(it) }
            editor.apply()

            partImagesMap.clear()
            vehicleImagesMap.clear()
            vehicles.clear()
            parts.clear()

            for (i in 0 until vehArr.length()) {
                val vj = vehArr.getJSONObject(i)
                val newVid = dbHelper.insertVehicle(
                    vj.getString("modelo"),
                    vj.getString("marca"),
                    vj.getInt("ano"),
                    vj.getString("info")
                )
                vehicles.add(
                    Vehicle(
                        newVid,
                        vj.getString("modelo"),
                        vj.getString("marca"),
                        vj.getInt("ano"),
                        vj.getString("info")
                    )
                )

                // imagens veículo
                val imgVehArr = vj.getJSONArray("images_vehicle")
                val listVeh = mutableListOf<Uri>()
                for (j in 0 until imgVehArr.length()) {
                    val u = Uri.parse(imgVehArr.getString(j))
                    contentResolver.takePersistableUriPermission(
                        u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    listVeh.add(u)
                }
                vehicleImagesMap[newVid] = listVeh
                saveImagesForVehicle(newVid)

                // peças
                val pars = vj.getJSONArray("parts")
                for (j in 0 until pars.length()) {
                    val pj = pars.getJSONObject(j)
                    val newPid = dbHelper.insertPart(
                        newVid,
                        pj.getString("piece_name"),
                        pj.getString("manufacturer"),
                        pj.getString("change_date"),
                        pj.getInt("interval_days"),
                        pj.getString("future_date"),
                        pj.getString("nf_number"),
                        pj.getString("category"),
                        pj.getString("info"),
                        pj.getInt("alert_threshold"),
                        pj.getDouble("value")
                    )
                    parts.add(
                        Part(
                            newPid,
                            newVid,
                            pj.getString("category"),
                            pj.getString("nf_number"),
                            pj.getString("piece_name"),
                            pj.getString("manufacturer"),
                            pj.getString("change_date"),
                            pj.getInt("interval_days"),
                            pj.getString("future_date"),
                            pj.getString("info"),
                            pj.getInt("alert_threshold"),
                            pj.getDouble("value")
                        )
                    )

                    // imagens peça
                    val imgArr = pj.getJSONArray("images_part")
                    val listPar = mutableListOf<Uri>()
                    for (k in 0 until imgArr.length()) {
                        val u = Uri.parse(imgArr.getString(k))
                        contentResolver.takePersistableUriPermission(
                            u, Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                        listPar.add(u)
                    }
                    partImagesMap[newPid] = listPar
                    saveImagesForPart(newPid)
                }
            }

            loadVehiclesFromDb()
            loadAlerts()
            Toast.makeText(this, "Importação concluída!", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "Erro na importação: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearVehicleForm() {
        // ... (código sem alterações)
        listOf(
            R.id.editTextModelo,
            R.id.editTextMarca,
            R.id.editTextAno,
            R.id.editTextInfo
        ).forEach { findViewById<EditText>(it).text.clear() }
    }

    private fun loadVehiclesFromDb() {
        // ... (código sem alterações)
        vehicles.clear()
        adapterVehicles.clear()
        val c = dbHelper.readableDatabase.query(
            VehicleEntry.TABLE_NAME,
            arrayOf(
                VehicleEntry._ID,
                VehicleEntry.COL_MODELO,
                VehicleEntry.COL_MARCA,
                VehicleEntry.COL_ANO,
                VehicleEntry.COL_INFO
            ), null, null, null, null,
            "${VehicleEntry._ID} DESC"
        )
        while (c.moveToNext()) {
            vehicles.add(
                Vehicle(
                    id     = c.getLong(c.getColumnIndexOrThrow(VehicleEntry._ID)),
                    modelo = c.getString(c.getColumnIndexOrThrow(VehicleEntry.COL_MODELO)),
                    marca  = c.getString(c.getColumnIndexOrThrow(VehicleEntry.COL_MARCA)),
                    ano    = c.getInt(c.getColumnIndexOrThrow(VehicleEntry.COL_ANO)),
                    info   = c.getString(c.getColumnIndexOrThrow(VehicleEntry.COL_INFO))
                )
            )
        }
        c.close()
        adapterVehicles.addAll(vehicles.map {
            "${it.modelo} / ${it.marca} / ${it.ano}" +
                    if (it.info.isNotEmpty()) " (${it.info})" else ""
        })
    }

    private fun vehicleContextMenu(v: Vehicle) {
        // ... (código sem alterações)
        AlertDialog.Builder(this)
            .setTitle("Veículo “${v.modelo}”")
            .setItems(arrayOf("Editar", "Excluir")) { _, which ->
                if (which == 0) editVehicle(v) else confirmDeleteVehicle(v)
            }
            .show()
    }

    private fun editVehicle(v: Vehicle) {
        // ... (código sem alterações)
        val L = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val inM  = EditText(this).apply { hint = "Modelo"; setText(v.modelo) }
        val inMa = EditText(this).apply { hint = "Marca";  setText(v.marca) }
        val inA  = EditText(this).apply {
            hint = "Ano"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(v.ano.toString())
        }
        val inInf = EditText(this).apply { hint = "Info"; setText(v.info) }
        listOf(inM, inMa, inA, inInf).forEach { L.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("Editar Veículo")
            .setView(L)
            .setPositiveButton("Salvar") { _, _ ->
                val nm  = inM.text.toString().trim()
                val nma = inMa.text.toString().trim()
                val na  = inA.text.toString().trim().toIntOrNull() ?: 0
                val ni  = inInf.text.toString().trim()
                if (nm.isNotEmpty() && nma.isNotEmpty() && na > 0) {
                    dbHelper.updateVehicle(v.id, nm, nma, na, ni)
                    loadVehiclesFromDb()
                    Toast.makeText(this, "Veículo atualizado", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Dados inválidos", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDeleteVehicle(v: Vehicle) {
        // ... (código sem alterações)
        AlertDialog.Builder(this)
            .setTitle("Excluir Veículo")
            .setMessage("Excluir “${v.modelo}”?")
            .setPositiveButton("Excluir") { _, _ ->
                dbHelper.deleteVehicle(v.id)
                loadVehiclesFromDb()
                Toast.makeText(this, "Veículo excluído", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showPartDetail(p: Part) {
        // ... (código sem alterações)
        currentPartId = p.id
        findViewById<TextView>(R.id.tvPartName).text        = "Nome: ${p.pieceName}"
        findViewById<TextView>(R.id.tvPartCategory).text    = "Categoria: ${p.category}"
        findViewById<TextView>(R.id.tvPartNfNumber).text    = "Nº NFS-e: ${p.nfNumber}"
        findViewById<TextView>(R.id.tvPartManufacturer).text= "Fabricante: ${p.manufacturer}"
        findViewById<TextView>(R.id.tvPartValue).text       = "Valor: R$ ${"%.2f".format(p.value)}"
        findViewById<TextView>(R.id.tvPartChangeDate).text  = "Data de Troca: ${p.changeDate}"
        findViewById<TextView>(R.id.tvPartInterval).text    = "Intervalo: ${p.intervalDays} dias"
        findViewById<TextView>(R.id.tvPartFutureDate).text  = "Próxima Troca: ${p.futureDate}"
        findViewById<TextView>(R.id.tvPartInfo).text        = if (p.info.isNotEmpty()) "Informações Adicionais: ${p.info}" else "Info: —"

        loadImagesForPart(p.id)
        val partLayout = findViewById<LinearLayout>(R.id.partImagesLayout)
        partLayout.removeAllViews()
        partImagesMap[p.id]?.forEach { partLayout.addView(createPartImageView(it)) }

        findViewById<Button>(R.id.buttonAddImage).setOnClickListener {
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            }.also { startActivityForResult(it, REQUEST_PART_IMAGE_PICK) }
        }

        findViewById<ScrollView>(R.id.detail_view).visibility      = View.GONE
        findViewById<ScrollView>(R.id.part_detail_view).visibility = View.VISIBLE
        findViewById<Button>(R.id.buttonBackPart).setOnClickListener {
            findViewById<ScrollView>(R.id.part_detail_view).visibility = View.GONE
            findViewById<ScrollView>(R.id.detail_view).visibility      = View.VISIBLE
        }
    }

    private fun savePart() {
        // ... (código sem alterações)
        if (currentVehicleId < 0) return
        val catIn   = findViewById<AutoCompleteTextView>(R.id.editTextCategory)
        val nfIn    = findViewById<EditText>(R.id.editTextNfNumber)
        val nmIn    = findViewById<EditText>(R.id.editTextPieceName)
        val manIn   = findViewById<EditText>(R.id.editTextPieceManufacturer)
        val valIn   = findViewById<EditText>(R.id.editTextPieceValue)
        val ivIn    = findViewById<EditText>(R.id.editTextIntervalDays)
        val dtIn    = findViewById<EditText>(R.id.editTextChangeDate)
        val thrIn   = findViewById<EditText>(R.id.editTextAlertThreshold)
        val infIn   = findViewById<EditText>(R.id.editTextPieceInfo)

        val category    = catIn.text.toString().trim()
        val nfNumber    = nfIn.text.toString().trim()
        val pieceName   = nmIn.text.toString().trim()
        val manufacturer= manIn.text.toString().trim()
        val value       = valIn.text.toString().replace(",",".").toDoubleOrNull() ?: 0.0
        val interval    = ivIn.text.toString().trim().toIntOrNull() ?: -1
        val changeDate  = dtIn.text.toString().trim()
        val threshold   = thrIn.text.toString().trim().toIntOrNull() ?: -1
        val info        = infIn.text.toString().trim()

        if (category.isEmpty() || nfNumber.isEmpty() || pieceName.isEmpty()
            || manufacturer.isEmpty() || interval < 0 || !isValidDate(changeDate)
        ) {
            Toast.makeText(this, "Preencha todos os campos corretamente.", Toast.LENGTH_SHORT).show()
            return
        }
        val futureDate = calcFuture(changeDate, interval)
        dbHelper.insertPart(
            currentVehicleId,
            pieceName, manufacturer,
            changeDate, interval, futureDate,
            nfNumber, category, info,
            threshold, value
        )
        updateCategorySuggestions()
        loadPartsForVehicle(currentVehicleId)
        loadAlerts()

        listOf(catIn, nfIn, nmIn, manIn, valIn, ivIn, dtIn, thrIn, infIn)
            .forEach { it.text.clear() }

        Toast.makeText(this, "Troca registrada!", Toast.LENGTH_SHORT).show()
    }

    private fun loadPartsForVehicle(vehicleId: Long) {
        // ... (código sem alterações)
        parts.clear()
        categoryList.clear()
        childrenList.clear()

        val cols = arrayOf(
            PartEntry._ID,
            PartEntry.COL_CATEGORY,
            PartEntry.COL_NF_NUMBER,
            PartEntry.COL_PIECE_NAME,
            PartEntry.COL_MANUFACTURER,
            PartEntry.COL_CHANGE_DATE,
            PartEntry.COL_INTERVAL_DAYS,
            PartEntry.COL_FUTURE_DATE,
            PartEntry.COL_INFO,
            PartEntry.COL_ALERT_THRESHOLD,
            PartEntry.COL_VALUE
        )
        val c = dbHelper.readableDatabase.query(
            PartEntry.TABLE_NAME, cols,
            "${PartEntry.COL_VEHICLE_ID}=?", arrayOf(vehicleId.toString()),
            null, null, "${PartEntry.COL_CHANGE_DATE} ASC"
        )
        while (c.moveToNext()) {
            parts.add(
                Part(
                    id             = c.getLong(c.getColumnIndexOrThrow(PartEntry._ID)),
                    vehicleId      = vehicleId,
                    category       = c.getString(c.getColumnIndexOrThrow(PartEntry.COL_CATEGORY)),
                    nfNumber       = c.getString(c.getColumnIndexOrThrow(PartEntry.COL_NF_NUMBER)),
                    pieceName      = c.getString(c.getColumnIndexOrThrow(PartEntry.COL_PIECE_NAME)),
                    manufacturer   = c.getString(c.getColumnIndexOrThrow(PartEntry.COL_MANUFACTURER)),
                    changeDate     = c.getString(c.getColumnIndexOrThrow(PartEntry.COL_CHANGE_DATE)),
                    intervalDays   = c.getInt(c.getColumnIndexOrThrow(PartEntry.COL_INTERVAL_DAYS)),
                    futureDate     = c.getString(c.getColumnIndexOrThrow(PartEntry.COL_FUTURE_DATE)),
                    info           = c.getString(c.getColumnIndexOrThrow(PartEntry.COL_INFO)),
                    alertThreshold = c.getInt(c.getColumnIndexOrThrow(PartEntry.COL_ALERT_THRESHOLD)),
                    value          = c.getDouble(c.getColumnIndexOrThrow(PartEntry.COL_VALUE))
                )
            )
        }
        c.close()

        val grouped = parts.groupBy { it.category }
        grouped.forEach { (cat, list) ->
            categoryList.add(cat)
            val sorted = if (!viewModeByPiece) list
            else list.sortedWith(compareBy({ it.pieceName }, { it.changeDate }))
            val lines = mutableListOf<String>()
            if (viewModeByPiece) {
                var lastName = ""
                sorted.forEach { p ->
                    if (p.pieceName != lastName) { lastName = p.pieceName; lines.add("— $lastName —") }
                    val key = cat to p.pieceName
                    if (!collapsedPieces.contains(key)) lines.add(formatPartLine(p))
                }
            } else {
                sorted.forEach { lines.add(formatPartLine(it)) }
            }
            childrenList.add(lines)
        }

        val groupData = categoryList.map { mapOf("CATEGORY" to it) }
        val childData = childrenList.map { lines ->
            lines.map { mapOf("CHILD" to it) }
        }
        val adapter = SimpleExpandableListAdapter(
            this,
            groupData, android.R.layout.simple_expandable_list_item_1,
            arrayOf("CATEGORY"), intArrayOf(android.R.id.text1),
            childData, android.R.layout.simple_expandable_list_item_1,
            arrayOf("CHILD"), intArrayOf(android.R.id.text1)
        )
        expandableListView.setAdapter(adapter)
    }

    private fun partContextMenu(p: Part) {
        // ... (código sem alterações)
        AlertDialog.Builder(this)
            .setTitle("Peça “${p.pieceName}”")
            .setItems(arrayOf("Editar", "Excluir")) { _, which ->
                if (which == 0) editPart(p) else confirmDeletePart(p)
            }
            .show()
    }

    private fun editPart(p: Part) {
        currentPartId = p.id
        val L = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val inCat  = AutoCompleteTextView(this).apply {
            hint = "Categoria"; setText(p.category); setAdapter(adapterCategories)
        }
        val inNf   = EditText(this).apply { hint = "Nº NFS-e";   setText(p.nfNumber) }
        val inName = EditText(this).apply { hint = "Nome Peça";   setText(p.pieceName) }
        val inMan  = EditText(this).apply { hint = "Fabricante";  setText(p.manufacturer) }
        val inVal  = EditText(this).apply {
            hint = "Valor (R$)"
            inputType = InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_CLASS_NUMBER
            setText(p.value.toString())
        }
        val inInt  = EditText(this).apply {
            hint = "Intervalo(dias)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(p.intervalDays.toString())
        }
        val inDt   = EditText(this).apply {
            hint = "Data(dd/MM/yyyy)"
            inputType = InputType.TYPE_CLASS_NUMBER // Adicionado para abrir teclado numérico
            setText(p.changeDate)
            // ALTERAÇÃO: Aplica o TextWatcher corrigido no diálogo de edição.
            addTextChangedListener(DateTextWatcher(this))
        }
        val inThr  = EditText(this).apply {
            hint = "Alerta X dias antes"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (p.alertThreshold >= 0) p.alertThreshold.toString() else "")
        }
        val inInf  = EditText(this).apply { hint = "Info"; setText(p.info) }
        listOf(inCat, inNf, inName, inMan, inVal, inInt, inDt, inThr, inInf).forEach { L.addView(it) }

        AlertDialog.Builder(this)
            .setTitle("Editar Troca")
            .setView(L)
            .setPositiveButton("Salvar") { _, _ ->
                val category    = inCat.text.toString().trim()
                val nfNumber    = inNf.text.toString().trim()
                val pieceName   = inName.text.toString().trim()
                val manufacturer= inMan.text.toString().trim()
                val value       = inVal.text.toString().replace(",",".").toDoubleOrNull() ?: 0.0
                val interval    = inInt.text.toString().trim().toIntOrNull() ?: -1
                val changeDate  = inDt.text.toString().trim()
                val threshold   = inThr.text.toString().trim().toIntOrNull() ?: -1
                val infoPart    = inInf.text.toString().trim()

                if (category.isEmpty() || nfNumber.isEmpty() || pieceName.isEmpty()
                    || manufacturer.isEmpty() || interval < 0 || !isValidDate(changeDate)
                ) {
                    Toast.makeText(this, "Dados inválidos", Toast.LENGTH_SHORT).show()
                } else {
                    val futureDate = calcFuture(changeDate, interval)
                    dbHelper.updatePart(
                        p.id,
                        pieceName, manufacturer,
                        changeDate, interval, futureDate,
                        nfNumber, category, infoPart,
                        threshold, value
                    )
                    updateCategorySuggestions()
                    loadPartsForVehicle(currentVehicleId)
                    loadAlerts()
                    Toast.makeText(this, "Troca atualizada", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirmDeletePart(p: Part) {
        // ... (código sem alterações)
        AlertDialog.Builder(this)
            .setTitle("Excluir Troca")
            .setMessage("Excluir “${p.pieceName}”?")
            .setPositiveButton("Excluir") { _, _ ->
                dbHelper.deletePart(p.id)
                prefs.edit().remove("images_part_${p.id}").apply()
                partImagesMap.remove(p.id)
                loadPartsForVehicle(currentVehicleId)
                loadAlerts()
                Toast.makeText(this, "Troca excluída", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun loadAlerts() {
        // ... (código sem alterações)
        adapterAlerts.clear()
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val now = Date()
        val cols = arrayOf(
            PartEntry.COL_VEHICLE_ID,
            PartEntry.COL_PIECE_NAME,
            PartEntry.COL_FUTURE_DATE,
            PartEntry.COL_ALERT_THRESHOLD
        )
        val c = dbHelper.readableDatabase.query(
            PartEntry.TABLE_NAME, cols, null, null, null, null, null
        )
        while (c.moveToNext()) {
            val vid  = c.getLong(c.getColumnIndexOrThrow(PartEntry.COL_VEHICLE_ID))
            val nm   = c.getString(c.getColumnIndexOrThrow(PartEntry.COL_PIECE_NAME))
            val fut  = c.getString(c.getColumnIndexOrThrow(PartEntry.COL_FUTURE_DATE))
            val thr  = c.getInt(c.getColumnIndexOrThrow(PartEntry.COL_ALERT_THRESHOLD))
            val due  = sdf.parse(fut) ?: continue
            val diff = ((due.time - now.time) / (24*3600*1000)).toInt()
            val limit = if (thr >= 0) thr else defaultThreshold
            if (diff in 0..limit) {
                val model = vehicles.find { it.id == vid }?.modelo ?: "Veículo#$vid"
                adapterAlerts.add("⚠️ $model → $nm em $diff dias")
            }
        }
        c.close()
        adapterAlerts.notifyDataSetChanged()
    }

    private fun showAlertsToasts() {
        // ... (código sem alterações)
        for (i in 0 until adapterAlerts.count) {
            Toast.makeText(this, adapterAlerts.getItem(i), Toast.LENGTH_LONG).show()
        }
    }

    // ALTERAÇÃO: Implementação corrigida e robusta do TextWatcher para datas.
    private class DateTextWatcher(private val editText: EditText) : TextWatcher {
        private var current = ""

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (s.toString() == current) {
                return
            }

            // Pega apenas os dígitos da entrada do usuário
            var clean = s.toString().replace("[^\\d]".toRegex(), "")
            if (clean.length > 8) {
                clean = clean.substring(0, 8) // Limita a 8 dígitos (ddmmyyyy)
            }

            // Reconstrói a string formatada
            val formatted = when {
                clean.length >= 5 -> "${clean.substring(0, 2)}/${clean.substring(2, 4)}/${clean.substring(4)}"
                clean.length >= 3 -> "${clean.substring(0, 2)}/${clean.substring(2)}"
                else -> clean
            }

            current = formatted

            // Atualiza o EditText sem causar um loop infinito
            editText.removeTextChangedListener(this)
            editText.setText(formatted)
            editText.setSelection(formatted.length) // Posiciona o cursor no final
            editText.addTextChangedListener(this)
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {}
        override fun afterTextChanged(s: Editable) {}
    }

    private fun updateCategorySuggestions() {
        val cats = dbHelper.getAllCategories()
        adapterCategories.clear()
        adapterCategories.addAll(cats)
        adapterCategories.notifyDataSetChanged()
    }

    private fun formatPartLine(p: Part): String =
        "${p.pieceName} | R$${"%.2f".format(p.value)}\n" +
                "${p.changeDate} → ${p.futureDate}"

    private fun calcFuture(date: String, days: Int): String {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val d   = sdf.parse(date) ?: return date
        val cal = Calendar.getInstance().apply { time = d; add(Calendar.DAY_OF_YEAR, days) }
        return sdf.format(cal.time)
    }

    private fun isValidDate(s: String): Boolean {
        if (s.length != 10) return false
        return try {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
            sdf.isLenient = false
            sdf.parse(s)
            true
        } catch (_: Exception) {
            false
        }
    }

    // O restante do código (imagens, dbhelper, data classes) permanece igual.
    // ...
    // ...
    private fun createPartImageView(uri: Uri): ImageView {
        val px = (200 * resources.displayMetrics.density).toInt()
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(px, px).apply { setMargins(8,8,8,8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageURI(uri)
            setOnClickListener {
                val dlg = Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val full = ImageView(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageURI(uri)
                    setOnClickListener { dlg.dismiss() }
                }
                dlg.setContentView(full)
                dlg.show()
            }
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Remover esta imagem?")
                    .setPositiveButton("Sim") { _, _ ->
                        (parent as? ViewGroup)?.removeView(this)
                        partImagesMap[currentPartId]?.remove(uri)
                        saveImagesForPart(currentPartId)
                    }
                    .setNegativeButton("Não", null)
                    .show()
                true
            }
        }
    }

    private fun saveImagesForPart(partId: Long) {
        val list = partImagesMap[partId]?.map(Uri::toString) ?: emptyList()
        prefs.edit().putString("images_part_$partId", list.joinToString(";")).apply()
    }

    private fun loadImagesForPart(partId: Long) {
        val stored = prefs.getString("images_part_$partId", "") ?: ""
        if (stored.isNotEmpty()) {
            partImagesMap[partId] = stored.split(";")
                .mapNotNull { Uri.parse(it) }
                .toMutableList()
        } else partImagesMap.remove(partId)
    }

    private fun createVehicleImageView(uri: Uri): ImageView {
        val px = (200 * resources.displayMetrics.density).toInt()
        return ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(px, px).apply { setMargins(8,8,8,8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageURI(uri)
            setOnClickListener {
                val dlg = Dialog(this@MainActivity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
                val full = ImageView(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setImageURI(uri)
                    setOnClickListener { dlg.dismiss() }
                }
                dlg.setContentView(full)
                dlg.show()
            }
            setOnLongClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Remover esta imagem do veículo?")
                    .setPositiveButton("Sim") { _, _ ->
                        (parent as? ViewGroup)?.removeView(this)
                        vehicleImagesMap[currentVehicleId]?.remove(uri)
                        saveImagesForVehicle(currentVehicleId)
                    }
                    .setNegativeButton("Não", null)
                    .show()
                true
            }
        }
    }

    private fun saveImagesForVehicle(vehicleId: Long) {
        val list = vehicleImagesMap[vehicleId]?.map(Uri::toString) ?: emptyList()
        prefs.edit().putString("images_vehicle_$vehicleId", list.joinToString(";")).apply()
    }

    private fun loadImagesForVehicle(vehicleId: Long) {
        val stored = prefs.getString("images_vehicle_$vehicleId", "") ?: ""
        if (stored.isNotEmpty()) {
            vehicleImagesMap[vehicleId] = stored.split(";")
                .mapNotNull { Uri.parse(it) }
                .toMutableList()
        } else vehicleImagesMap.remove(vehicleId)
    }

    private class VehicleDbHelper(ctx: AppCompatActivity) :
        SQLiteOpenHelper(ctx, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE ${VehicleEntry.TABLE_NAME} (" +
                        "${VehicleEntry._ID} INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "${VehicleEntry.COL_MODELO} TEXT NOT NULL," +
                        "${VehicleEntry.COL_MARCA}  TEXT NOT NULL," +
                        "${VehicleEntry.COL_ANO}    INTEGER NOT NULL," +
                        "${VehicleEntry.COL_INFO}   TEXT" +
                        ")"
            )
            db.execSQL(
                "CREATE TABLE ${PartEntry.TABLE_NAME} (" +
                        "${PartEntry._ID} INTEGER PRIMARY KEY AUTOINCREMENT," +
                        "${PartEntry.COL_VEHICLE_ID} INTEGER NOT NULL," +
                        "${PartEntry.COL_CATEGORY} TEXT NOT NULL," +
                        "${PartEntry.COL_NF_NUMBER} TEXT NOT NULL," +
                        "${PartEntry.COL_PIECE_NAME} TEXT NOT NULL," +
                        "${PartEntry.COL_MANUFACTURER} TEXT NOT NULL," +
                        "${PartEntry.COL_CHANGE_DATE} TEXT NOT NULL," +
                        "${PartEntry.COL_INTERVAL_DAYS} INTEGER NOT NULL," +
                        "${PartEntry.COL_FUTURE_DATE} TEXT NOT NULL," +
                        "${PartEntry.COL_INFO} TEXT," +
                        "${PartEntry.COL_ALERT_THRESHOLD} INTEGER NOT NULL DEFAULT -1," +
                        "${PartEntry.COL_VALUE} REAL NOT NULL DEFAULT 0," +
                        "FOREIGN KEY(${PartEntry.COL_VEHICLE_ID}) REFERENCES " +
                        "${VehicleEntry.TABLE_NAME}(${VehicleEntry._ID})" +
                        ")"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            if (oldV < 2) onCreate(db)
            if (oldV < 3) {
                db.execSQL(
                    "ALTER TABLE ${PartEntry.TABLE_NAME} " +
                            "ADD COLUMN ${PartEntry.COL_NF_NUMBER} TEXT NOT NULL DEFAULT ''"
                )
                db.execSQL(
                    "ALTER TABLE ${PartEntry.TABLE_NAME} " +
                            "ADD COLUMN ${PartEntry.COL_CATEGORY} TEXT NOT NULL DEFAULT ''"
                )
            }
            if (oldV < 4) {
                db.execSQL(
                    "ALTER TABLE ${PartEntry.TABLE_NAME} " +
                            "ADD COLUMN ${PartEntry.COL_ALERT_THRESHOLD} INTEGER NOT NULL DEFAULT -1"
                )
            }
            if (oldV < 5) {
                db.execSQL(
                    "ALTER TABLE ${PartEntry.TABLE_NAME} " +
                            "ADD COLUMN ${PartEntry.COL_VALUE} REAL NOT NULL DEFAULT 0"
                )
            }
        }

        fun insertVehicle(modelo: String, marca: String, ano: Int, info: String): Long {
            var id: Long = -1
            writableDatabase.use { db ->
                val cv = ContentValues().apply {
                    put(VehicleEntry.COL_MODELO, modelo)
                    put(VehicleEntry.COL_MARCA,  marca)
                    put(VehicleEntry.COL_ANO,    ano)
                    put(VehicleEntry.COL_INFO,   info)
                }
                id = db.insert(VehicleEntry.TABLE_NAME, null, cv)
            }
            return id
        }

        fun updateVehicle(id: Long, modelo: String, marca: String, ano: Int, info: String) {
            writableDatabase.use { db ->
                val cv = ContentValues().apply {
                    put(VehicleEntry.COL_MODELO, modelo)
                    put(VehicleEntry.COL_MARCA,  marca)
                    put(VehicleEntry.COL_ANO,    ano)
                    put(VehicleEntry.COL_INFO,   info)
                }
                db.update(VehicleEntry.TABLE_NAME, cv, "${VehicleEntry._ID}=?", arrayOf(id.toString()))
            }
        }

        fun deleteVehicle(id: Long) {
            writableDatabase.use { db ->
                db.delete(PartEntry.TABLE_NAME, "${PartEntry.COL_VEHICLE_ID}=?", arrayOf(id.toString()))
                db.delete(VehicleEntry.TABLE_NAME, "${VehicleEntry._ID}=?", arrayOf(id.toString()))
            }
        }

        fun insertPart(
            vehicleId: Long,
            pieceName: String,
            manufacturer: String,
            changeDate: String,
            intervalDays: Int,
            futureDate: String,
            nfNumber: String,
            category: String,
            info: String,
            alertThreshold: Int,
            value: Double
        ): Long {
            var id: Long = -1
            writableDatabase.use { db ->
                val cv = ContentValues().apply {
                    put(PartEntry.COL_VEHICLE_ID,    vehicleId)
                    put(PartEntry.COL_PIECE_NAME,     pieceName)
                    put(PartEntry.COL_MANUFACTURER,   manufacturer)
                    put(PartEntry.COL_CHANGE_DATE,    changeDate)
                    put(PartEntry.COL_INTERVAL_DAYS,  intervalDays)
                    put(PartEntry.COL_FUTURE_DATE,    futureDate)
                    put(PartEntry.COL_NF_NUMBER,      nfNumber)
                    put(PartEntry.COL_CATEGORY,       category)
                    put(PartEntry.COL_INFO,           info)
                    put(PartEntry.COL_ALERT_THRESHOLD, alertThreshold)
                    put(PartEntry.COL_VALUE,           value)
                }
                id = db.insert(PartEntry.TABLE_NAME, null, cv)
            }
            return id
        }

        fun updatePart(
            id: Long,
            pieceName: String,
            manufacturer: String,
            changeDate: String,
            intervalDays: Int,
            futureDate: String,
            nfNumber: String,
            category: String,
            info: String,
            alertThreshold: Int,
            value: Double
        ) {
            writableDatabase.use { db ->
                val cv = ContentValues().apply {
                    put(PartEntry.COL_PIECE_NAME,      pieceName)
                    put(PartEntry.COL_MANUFACTURER,    manufacturer)
                    put(PartEntry.COL_CHANGE_DATE,     changeDate)
                    put(PartEntry.COL_INTERVAL_DAYS,   intervalDays)
                    put(PartEntry.COL_FUTURE_DATE,     futureDate)
                    put(PartEntry.COL_NF_NUMBER,       nfNumber)
                    put(PartEntry.COL_CATEGORY,        category)
                    put(PartEntry.COL_INFO,            info)
                    put(PartEntry.COL_ALERT_THRESHOLD, alertThreshold)
                    put(PartEntry.COL_VALUE,           value)
                }
                db.update(PartEntry.TABLE_NAME, cv, "${PartEntry._ID}=?", arrayOf(id.toString()))
            }
        }

        fun deletePart(id: Long) {
            writableDatabase.use { db ->
                db.delete(PartEntry.TABLE_NAME, "${PartEntry._ID}=?", arrayOf(id.toString()))
            }
        }

        fun getAllCategories(): List<String> {
            val list = mutableListOf<String>()
            val c = readableDatabase.query(
                PartEntry.TABLE_NAME,
                arrayOf(PartEntry.COL_CATEGORY),
                null, null,
                PartEntry.COL_CATEGORY, null, null
            )
            while (c.moveToNext()) {
                list.add(c.getString(c.getColumnIndexOrThrow(PartEntry.COL_CATEGORY)))
            }
            c.close()
            return list
        }

        companion object {
            const val DATABASE_NAME    = "vehicles.db"
            const val DATABASE_VERSION = 5
        }
    }

    object VehicleEntry {
        const val TABLE_NAME = "vehicles"
        const val _ID        = "_id"
        const val COL_MODELO = "modelo"
        const val COL_MARCA  = "marca"
        const val COL_ANO    = "ano"
        const val COL_INFO   = "info"
    }

    object PartEntry {
        const val TABLE_NAME         = "parts"
        const val _ID                = "_id"
        const val COL_VEHICLE_ID     = "vehicle_id"
        const val COL_CATEGORY       = "category"
        const val COL_NF_NUMBER      = "nf_number"
        const val COL_PIECE_NAME     = "piece_name"
        const val COL_MANUFACTURER   = "manufacturer"
        const val COL_CHANGE_DATE    = "change_date"
        const val COL_INTERVAL_DAYS  = "interval_days"
        const val COL_FUTURE_DATE    = "future_date"
        const val COL_INFO           = "info"
        const val COL_ALERT_THRESHOLD= "alert_threshold"
        const val COL_VALUE          = "value"
    }

    data class Vehicle(
        val id: Long,
        val modelo: String,
        val marca: String,
        val ano: Int,
        val info: String
    )

    data class Part(
        val id: Long,
        val vehicleId: Long,
        val category: String,
        val nfNumber: String,
        val pieceName: String,
        val manufacturer: String,
        val changeDate: String,
        val intervalDays: Int,
        val futureDate: String,
        val info: String,
        val alertThreshold: Int,
        val value: Double
    )
}
