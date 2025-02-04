package org.uoyabause.android.phone

import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.fragment.app.viewModels
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import com.google.common.collect.ImmutableList
import com.google.firebase.analytics.ktx.logEvent
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import io.reactivex.observers.DisposableSingleObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.devmiyax.yabasanshiro.BuildConfig
import org.devmiyax.yabasanshiro.R
import org.uoyabause.android.BillingViewModel
import org.uoyabause.android.GameSelectPresenter
import org.uoyabause.android.ShowPinInFragment
import org.uoyabause.android.YabauseApplication
import org.uoyabause.android.YabauseStorage
import org.uoyabause.android.billing.BillingClientWrapper
import org.uoyabause.android.phone.placeholder.PlaceholderContent
import org.uoyabause.android.repository.SubscriptionDataRepository
import java.io.File


/**
 * A fragment representing a list of Items.
 */
class BackupBackupItemFragment : Fragment() {

    private var columnCount = 1
    private lateinit var presenter_: GameSelectPresenter
    private val viewModel by viewModels<BillingViewModel>()
    val connectionObserver = Observer<Boolean> { isConnecteed ->
        Log.d(TAG,"isConnected ${isConnecteed}")
    }
    private var authchecker: DisposableSingleObserver<FirebaseUser>? = null

    private var signInActivityLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        presenter_.onSignIn(result.resultCode, result.data)
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            PlaceholderContent.onSiginIn()
            startSubscribe()
        }else{
            fragmentManager?.beginTransaction()?.remove(this)?.commit();
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            columnCount = it.getInt(ARG_COLUMN_COUNT)
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            presenter_.signIn(signInActivityLauncher)
            return
        }else {
            startSubscribe()
        }
    }

    fun startSubscribe(){
        viewModel.billingConnectionState.observe(this,connectionObserver)

        val remoteConfig = FirebaseRemoteConfig.getInstance()
        val configSettings = FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(3600)
            .build()
        remoteConfig.setConfigSettingsAsync(configSettings)
        remoteConfig.setDefaultsAsync(R.xml.config)

        if(!remoteConfig.getBoolean("is_enable_subscription")){
            presenter_.isOnSubscription = true
        }else {
            lifecycleScope.launchWhenStarted {
                viewModel.userCurrentSubscriptionFlow.collect { collectedSubscriptions ->
                    when {
                        collectedSubscriptions.hasPrepaidBasic == true -> {
                            Log.d(TAG, "hasPrepaidBasic")
                            if (!presenter_.isOnSubscription) {
                                presenter_.isOnSubscription = true
                                onEnableSubscribe()
                                presenter_.syncBackup()
                            }

                        }
                        collectedSubscriptions.hasRenewableBasic == true -> {
                            Log.d(TAG, "hasRenewableBasic")
                            if (!presenter_.isOnSubscription) {
                                presenter_.isOnSubscription = true
                                onEnableSubscribe()
                                presenter_.syncBackup()
                            }
                        }
                        else -> {
                            Log.d(TAG, "else")
                            if (presenter_.isOnSubscription) {
                                presenter_.isOnSubscription = false
                                onDiaslbeSubscribe()
                            }
                        }
                    }
                }
            }
        }

    }

    fun onDiaslbeSubscribe() {
        val chk = this.view?.findViewById<CheckBox>(R.id.checkBoxAutoBackup)
        chk?.isEnabled = false
    }


    fun onEnableSubscribe() {
        val chk = this.view?.findViewById<CheckBox>(R.id.checkBoxAutoBackup)
        chk?.isEnabled = true
    }


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View? {
        val view = inflater.inflate(R.layout.fragment_backupbackup_item_list, container, false)
        val cview = view.findViewById<RecyclerView>(R.id.BackupBackuplist)


        // Set the adapter
        if (cview is RecyclerView) {
            with(cview) {
                layoutManager = when {
                    columnCount <= 1 -> LinearLayoutManager(context)
                    else -> GridLayoutManager(context, columnCount)
                }
                adapter = BackupBackupItemRecyclerViewAdapter(PlaceholderContent.ITEMS)
                PlaceholderContent.adapter = adapter as BackupBackupItemRecyclerViewAdapter

                val a = adapter as BackupBackupItemRecyclerViewAdapter
                a.setRollback {

                    val downloadFilename =
                        PlaceholderContent.ITEMS[it].rawdata["filename"] as String

                    val key = PlaceholderContent.ITEMS[it].key as String

                    presenter_.rollBackMemory(downloadFilename,key)

                }
            }
        }

        val chk = view.findViewById<CheckBox>(R.id.checkBoxAutoBackup)
        val sharedPref = PreferenceManager.getDefaultSharedPreferences(this.requireActivity())
        if( presenter_.isOnSubscription ) {
            chk.isChecked = sharedPref.getBoolean("auto_backup", true)
            if (chk.isChecked == false) {
                val touchInterceptorView = view.findViewById<View>(R.id.touch_interceptor_view)
                touchInterceptorView.setOnTouchListener { _, _ -> true }
                cview.alpha = 0.5f
            } else {
                val touchInterceptorView = view.findViewById<View>(R.id.touch_interceptor_view)
                touchInterceptorView.setOnTouchListener { _, _ -> false }
                cview.alpha = 1.0f
            }
        }else{
            chk.isEnabled = false
            chk.isChecked = false
        }

        chk.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                // チェックが入ったときの処理
                //Log.d(TAG, "CheckBox is checked")
                val touchInterceptorView = view.findViewById<View>(R.id.touch_interceptor_view)
                touchInterceptorView.setOnTouchListener { _, _ -> false }
                cview.alpha = 1.0f

                val sharedPref = PreferenceManager.getDefaultSharedPreferences(this.requireActivity())
                sharedPref.edit().putBoolean("auto_backup",true).commit()

                presenter_.syncBackup()


            } else {
                // チェックが外れたときの処理
                //Log.d(TAG, "CheckBox is unchecked")
                val touchInterceptorView = view.findViewById<View>(R.id.touch_interceptor_view)
                touchInterceptorView.setOnTouchListener { _, _ -> true }
                cview.alpha = 0.5f

                val sharedPref = PreferenceManager.getDefaultSharedPreferences(this.requireActivity())
                sharedPref.edit().putBoolean("auto_backup",false).commit()
            }
        }



        return view
    }

    companion object {
        private const val MAX_CURRENT_PURCHASES_ALLOWED = 1
        // TODO: Customize parameter argument names
        const val ARG_COLUMN_COUNT = "column-count"
        const val ARG_PRESENTER = "presenter"

        const val TAG ="backup-backup-fragment"

        // TODO: Customize parameter initialization
        @JvmStatic
        fun newInstance(columnCount: Int, presenter: GameSelectPresenter ) =
            BackupBackupItemFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_COLUMN_COUNT, columnCount)
                }
                presenter_ = presenter
            }
    }
}