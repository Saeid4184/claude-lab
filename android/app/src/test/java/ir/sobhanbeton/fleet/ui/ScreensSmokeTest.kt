package ir.sobhanbeton.fleet.ui

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import ir.sobhanbeton.fleet.R
import ir.sobhanbeton.fleet.data.Session
import ir.sobhanbeton.fleet.data.UserProfile
import ir.sobhanbeton.fleet.data.UserRole
import ir.sobhanbeton.fleet.ui.drivers.DriverDetailActivity
import ir.sobhanbeton.fleet.ui.drivers.DriverFormActivity
import ir.sobhanbeton.fleet.ui.drivers.DriversActivity
import ir.sobhanbeton.fleet.ui.vehicles.VehicleDetailActivity
import ir.sobhanbeton.fleet.ui.vehicles.VehicleFormActivity
import ir.sobhanbeton.fleet.ui.vehicles.VehiclesActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A plain Application so the real FactoryApp's Firebase setup doesn't run; Firebase is
 *  initialised with dummy options instead (nothing here talks to a server). */
class TestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (FirebaseApp.getApps(this).isEmpty()) {
            FirebaseApp.initializeApp(
                this,
                FirebaseOptions.Builder()
                    .setProjectId("smoke-test")
                    .setApplicationId("1:1:android:1")
                    .setApiKey("smoke-test-key")
                    .build()
            )
        }
        // Robolectric doesn't run androidx.startup, which initialises WorkManager on a device.
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(this)
    }
}

/**
 * Opens every screen with the real layouts and theme (no emulator needed) — catches inflation
 * and wiring crashes that only show up at runtime, e.g. a widget whose style needs a different
 * theme or a binding to a view that no longer exists.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class)
class ScreensSmokeTest {

    @Before
    fun signInAsExpert() {
        Session.set(UserProfile(uid = "u1", name = "کارشناس", email = "e@x.ir", role = UserRole.EXPERT))
    }

    private fun <T : Activity> open(cls: Class<T>, intent: Intent? = null): T {
        val i = intent ?: Intent(ApplicationProvider.getApplicationContext(), cls)
        return Robolectric.buildActivity(cls, i).setup().get()
    }

    @Test fun main() { open(MainActivity::class.java) }
    @Test fun settings() { open(SettingsActivity::class.java) }
    @Test fun vehicles() { open(VehiclesActivity::class.java) }
    @Test fun drivers() { open(DriversActivity::class.java) }
    @Test fun expiring() { open(ExpiringDocumentsActivity::class.java) }
    @Test fun bulkImport() { open(BulkImportActivity::class.java) }
    @Test fun fuel() { open(ir.sobhanbeton.fleet.ui.fuel.FuelActivity::class.java) }
    @Test fun inspections() { open(ir.sobhanbeton.fleet.ui.inspections.InspectionsActivity::class.java) }
    @Test fun workOrders() { open(ir.sobhanbeton.fleet.ui.repairs.WorkOrdersActivity::class.java) }
    @Test fun workOrderForm() { open(ir.sobhanbeton.fleet.ui.repairs.WorkOrderFormActivity::class.java) }
    @Test fun services() { open(ir.sobhanbeton.fleet.ui.services.ServicesActivity::class.java) }
    @Test fun serviceLogForm() { open(ir.sobhanbeton.fleet.ui.services.ServiceLogFormActivity::class.java) }
    @Test fun servicePlanForm() { open(ir.sobhanbeton.fleet.ui.services.ServicePlanFormActivity::class.java) }
    @Test fun expenseNotes() { open(ir.sobhanbeton.fleet.ui.expenses.ExpenseNotesActivity::class.java) }
    @Test fun expenseNoteForm() { open(ir.sobhanbeton.fleet.ui.expenses.ExpenseNoteFormActivity::class.java) }

    @Test
    fun inspectionFormInflates() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        // Unknown vehicle: the form inflates, then closes itself.
        open(
            ir.sobhanbeton.fleet.ui.inspections.InspectionFormActivity::class.java,
            Intent(ctx, ir.sobhanbeton.fleet.ui.inspections.InspectionFormActivity::class.java).putExtra("vehicle_id", "missing")
        )
    }

    @Test
    fun inspectionFormBuildsDiagramForEveryCategory() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val dao = ir.sobhanbeton.fleet.data.AppDatabase.getInstance(ctx).vehicleDao()
        for (category in ir.sobhanbeton.fleet.data.VehicleCategory.values()) {
            val id = "v-${category.name}"
            kotlinx.coroutines.runBlocking {
                dao.upsert(ir.sobhanbeton.fleet.data.VehicleEntity(id = id, plate = "12345", category = category.name))
            }
            val activity = open(
                ir.sobhanbeton.fleet.ui.inspections.InspectionFormActivity::class.java,
                Intent(ctx, ir.sobhanbeton.fleet.ui.inspections.InspectionFormActivity::class.java).putExtra("vehicle_id", id)
            )
            // Room runs on a background executor; wait until the form is built.
            val button = activity.findViewById<TextView>(R.id.btnSubmit)
            repeat(50) {
                if (!button.isEnabled) {
                    Thread.sleep(20)
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                }
            }
            assertTrue("form for $category", button.isEnabled)
            val diagram = activity.findViewById<ir.sobhanbeton.fleet.ui.view.VehicleDiagramView>(R.id.diagramView)
            val expected = ir.sobhanbeton.fleet.data.InspectionCatalog.partsFor(category).size -
                ir.sobhanbeton.fleet.data.InspectionCatalog.nonSpatialParts.size
            org.junit.Assert.assertEquals("diagram parts for $category", expected, diagram.currentResults().size)
        }
    }

    @Test
    fun workOrderDetail() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        open(
            ir.sobhanbeton.fleet.ui.repairs.WorkOrderDetailActivity::class.java,
            Intent(ctx, ir.sobhanbeton.fleet.ui.repairs.WorkOrderDetailActivity::class.java).putExtra("work_order_id", "missing")
        )
    }

    @Test
    fun managerCannotOpenExpenseNotes() {
        Session.set(UserProfile(uid = "u2", name = "مدیر", email = "m@x.ir", role = UserRole.MANAGER))
        val activity = Robolectric.buildActivity(ir.sobhanbeton.fleet.ui.expenses.ExpenseNotesActivity::class.java).setup().get()
        assertTrue(activity.isFinishing)
    }

    @Test
    fun fuelFormRequiresVehicle() {
        val activity = open(ir.sobhanbeton.fleet.ui.fuel.FuelFormActivity::class.java)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        activity.findViewById<TextView>(R.id.btnSave).performClick()
        val til = activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilVehicle)
        assertTrue(til.error != null)
    }

    @Test
    fun vehicleDetail() {
        open(VehicleDetailActivity::class.java, Intent(ApplicationProvider.getApplicationContext(), VehicleDetailActivity::class.java).putExtra("vehicle_id", "missing"))
    }

    @Test
    fun driverDetail() {
        open(DriverDetailActivity::class.java, Intent(ApplicationProvider.getApplicationContext(), DriverDetailActivity::class.java).putExtra("driver_id", "missing"))
    }

    @Test
    fun vehicleFormValidatesRequiredPlate() {
        val activity = open(VehicleFormActivity::class.java)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        activity.findViewById<TextView>(R.id.btnSave).performClick()
        val til = activity.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilPlate)
        assertTrue(til.error != null)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun driverFormOpensDatePicker() {
        val activity = open(DriverFormActivity::class.java)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        activity.findViewById<TextView>(R.id.etLicenseExpiry).performClick()
    }

    @Test
    fun managerCannotOpenForms() {
        Session.set(UserProfile(uid = "u2", name = "مدیر", email = "m@x.ir", role = UserRole.MANAGER))
        val activity = Robolectric.buildActivity(VehicleFormActivity::class.java).setup().get()
        assertTrue(activity.isFinishing)
    }
}
