package __NAMESPACE__

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val host = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        val navController = host.navController

        // All three are top-level, so none of them shows an Up arrow. The bar's menu item ids are
        // the destination ids, which is what lets the bar and the graph stay in step by themselves.
        val appBar = AppBarConfiguration(
            setOf(R.id.homeFragment, R.id.dashboardFragment, R.id.notificationsFragment),
        )
        setSupportActionBar(findViewById(R.id.toolbar))
        setupActionBarWithNavController(navController, appBar)
        findViewById<BottomNavigationView>(R.id.bottom_nav).setupWithNavController(navController)
    }
}
