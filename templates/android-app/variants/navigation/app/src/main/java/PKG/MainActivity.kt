package __NAMESPACE__

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.navigation.NavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var appBar: AppBarConfiguration
    private lateinit var navController: NavController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val host = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        navController = host.navController

        val drawer = findViewById<DrawerLayout>(R.id.drawer_layout)
        // Naming the drawer here is what turns the Up arrow into the hamburger, and what makes the
        // three destinations top-level rather than a stack you back out of.
        appBar = AppBarConfiguration(
            setOf(R.id.homeFragment, R.id.dashboardFragment, R.id.notificationsFragment),
            drawer,
        )
        setSupportActionBar(findViewById(R.id.toolbar))
        setupActionBarWithNavController(navController, appBar)
        findViewById<NavigationView>(R.id.nav_view).setupWithNavController(navController)
    }

    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp(appBar) || super.onSupportNavigateUp()
}
