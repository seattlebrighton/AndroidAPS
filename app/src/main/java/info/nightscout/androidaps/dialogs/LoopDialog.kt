package info.nightscout.androidaps.dialogs

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.fragment.app.FragmentManager
import dagger.android.support.DaggerDialogFragment
import info.nightscout.androidaps.MainApp
import info.nightscout.androidaps.R
import info.nightscout.androidaps.activities.ErrorHelperActivity
import info.nightscout.androidaps.databinding.DialogLoopBinding
import info.nightscout.androidaps.events.EventPreferenceChange
import info.nightscout.androidaps.events.EventRefreshOverview
import info.nightscout.androidaps.interfaces.*
import info.nightscout.androidaps.logging.AAPSLogger
import info.nightscout.androidaps.plugins.aps.loop.LoopPlugin
import info.nightscout.androidaps.plugins.aps.loop.events.EventNewOpenLoopNotification
import info.nightscout.androidaps.plugins.bus.RxBusWrapper
import info.nightscout.androidaps.plugins.configBuilder.ConfigBuilderPlugin
import info.nightscout.androidaps.plugins.configBuilder.ConstraintChecker
import info.nightscout.androidaps.plugins.constraints.objectives.ObjectivesPlugin
import info.nightscout.androidaps.queue.Callback
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.ToastUtils
import info.nightscout.androidaps.utils.alertDialogs.OKDialog
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.sharedPreferences.SP
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import javax.inject.Inject

class LoopDialog : DaggerDialogFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var mainApp: MainApp
    @Inject lateinit var sp: SP
    @Inject lateinit var rxBus: RxBusWrapper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var resourceHelper: ResourceHelper
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var loopPlugin: LoopPlugin
    @Inject lateinit var objectivesPlugin: ObjectivesPlugin
    @Inject lateinit var activePlugin: ActivePluginProvider
    @Inject lateinit var constraintChecker: ConstraintChecker
    @Inject lateinit var commandQueue: CommandQueueProvider
    @Inject lateinit var configBuilderPlugin: ConfigBuilderPlugin

    private var disposable: CompositeDisposable = CompositeDisposable()

    private var showOkCancel: Boolean = true
    private var _binding: DialogLoopBinding? = null
    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onSaveInstanceState(savedInstanceState: Bundle) {
        super.onSaveInstanceState(savedInstanceState)
        savedInstanceState.putInt("showOkCancel", if (showOkCancel) 1 else 0)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View? {
        // load data from bundle
        (savedInstanceState ?: arguments)?.let { bundle ->
            showOkCancel = bundle.getInt("showOkCancel", 1) == 1
        }
        dialog?.window?.requestFeature(Window.FEATURE_NO_TITLE)
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        isCancelable = true
        dialog?.setCanceledOnTouchOutside(false)

        _binding = DialogLoopBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        updateGUI("LoopDialogOnViewCreated")

        binding.overviewCloseloop.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewLgsloop.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewOpenloop.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewDisable.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewEnable.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewResume.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewReconnect.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewSuspend1h.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewSuspend2h.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewSuspend3h.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewSuspend10h.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewDisconnect15m.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewDisconnect30m.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewDisconnect1h.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewDisconnect2h.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }
        binding.overviewDisconnect3h.setOnClickListener { if(showOkCancel) onClick_OkCancelEnabled(it) else onClick(it); dismiss() }

        // cancel button
        binding.cancel.setOnClickListener { dismiss() }

        // bus
        disposable.add(rxBus
            .toObservable(EventNewOpenLoopNotification::class.java)
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe({
                activity?.runOnUiThread { updateGUI("EventNewOpenLoopNotification") }
            }, { fabricPrivacy.logException(it) })
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        disposable.clear()
    }

    fun updateGUI(from: String) {
        aapsLogger.debug("UpdateGUI from $from")
        val pumpDescription: PumpDescription = activePlugin.activePump.pumpDescription
        val closedLoopAllowed = objectivesPlugin.isClosedLoopAllowed(Constraint(true))
        val lgsEnabled = objectivesPlugin.isLgsAllowed(Constraint(true))
        var APSmode = sp.getString(R.string.key_aps_mode, "open")
        if (profileFunction.isProfileValid("LoopDialogUpdateGUI")) {
            if (loopPlugin.isEnabled(PluginType.LOOP)) {
                if (closedLoopAllowed.value()) {
                    binding.overviewCloseloop.visibility = if (APSmode == "closed") View.GONE else View.VISIBLE
                    binding.overviewLgsloop.visibility = if (APSmode == "lgs") View.GONE else View.VISIBLE
                    binding.overviewOpenloop.visibility = if (APSmode == "open") View.GONE else View.VISIBLE
                } else if (lgsEnabled.value() ) {
                    binding.overviewCloseloop.visibility = View.GONE
                    binding.overviewLgsloop.visibility = if (APSmode == "lgs") View.GONE else View.VISIBLE
                    binding.overviewOpenloop.visibility = if (APSmode == "open") View.GONE else View.VISIBLE
                } else {
                    binding.overviewCloseloop.visibility = View.GONE
                    binding.overviewLgsloop.visibility = View.GONE
                    binding.overviewOpenloop.visibility = View.GONE
                }
                binding.overviewEnable.visibility = View.GONE          //sp.getBoolean(R.string.key_usesuperbolus, false).toVisibility()
                binding.overviewDisable.visibility = View.VISIBLE
               if (!loopPlugin.isSuspended) {
                    binding.overviewSuspendHeader.text=resourceHelper.gs(R.string.suspendloop)
                    binding.overviewResume.visibility = View.GONE
                    binding.overviewSuspendButtons.visibility=View.VISIBLE
                    binding.overviewSuspend.visibility=View.VISIBLE
                } else {
                    if (!loopPlugin.isDisconnected) {
                        binding.overviewSuspendHeader.text = resourceHelper.gs(R.string.resumeloop)
                        binding.overviewResume.visibility = View.VISIBLE
                        binding.overviewSuspendButtons.visibility=View.GONE
                        binding.overviewSuspend.visibility=View.VISIBLE
                    } else
                        binding.overviewSuspend.visibility = View.GONE
                }
            } else {
                binding.overviewEnable.visibility = View.VISIBLE
                binding.overviewDisable.visibility = View.GONE
                binding.overviewSuspend.visibility = View.GONE
            }
            if (!loopPlugin.isDisconnected) {
                binding.overviewPumpHeader.text = resourceHelper.gs(R.string.disconnectpump)
                binding.overviewDisconnect15m.visibility = if (pumpDescription.tempDurationStep15mAllowed) View.VISIBLE else View.GONE
                binding.overviewDisconnect30m.visibility = if (pumpDescription.tempDurationStep30mAllowed) View.VISIBLE else View.GONE
                binding.overviewDisconnectButtons.visibility = View.VISIBLE
                binding.overviewReconnect.visibility = View.GONE
            } else {
                binding.overviewPumpHeader.text = resourceHelper.gs(R.string.reconnect)
                binding.overviewDisconnectButtons.visibility = View.GONE
                binding.overviewReconnect.visibility = View.VISIBLE
            }
        }
        val profile = profileFunction.getProfile()
        val profileStore = activePlugin.activeProfileInterface.profile

        if (profile == null || profileStore == null) {
            ToastUtils.showToastInUiThread(mainApp, resourceHelper.gs(R.string.noprofile))
            dismiss()
            return
        }

    }

    fun onClick_OkCancelEnabled(v: View): Boolean {
        var description = ""
        when(v.id) {
            R.id.overview_closeloop         -> description = resourceHelper.gs(R.string.closedloop)
            R.id.overview_lgsloop           -> description = resourceHelper.gs(R.string.lowglucosesuspend)
            R.id.overview_openloop          -> description = resourceHelper.gs(R.string.openloop)
            R.id.overview_disable           -> description = resourceHelper.gs(R.string.disableloop)
            R.id.overview_enable            -> description = resourceHelper.gs(R.string.enableloop)
            R.id.overview_resume            -> description = resourceHelper.gs(R.string.resume)
            R.id.overview_reconnect         -> description = resourceHelper.gs(R.string.reconnect)
            R.id.overview_suspend_1h        -> description = resourceHelper.gs(R.string.suspendloopfor1h)
            R.id.overview_suspend_2h        -> description = resourceHelper.gs(R.string.suspendloopfor2h)
            R.id.overview_suspend_3h        -> description = resourceHelper.gs(R.string.suspendloopfor3h)
            R.id.overview_suspend_10h       -> description = resourceHelper.gs(R.string.suspendloopfor10h)
            R.id.overview_disconnect_15m    -> description = resourceHelper.gs(R.string.disconnectpumpfor15m)
            R.id.overview_disconnect_30m    -> description = resourceHelper.gs(R.string.disconnectpumpfor30m)
            R.id.overview_disconnect_1h     -> description = resourceHelper.gs(R.string.disconnectpumpfor1h)
            R.id.overview_disconnect_2h     -> description = resourceHelper.gs(R.string.disconnectpumpfor2h)
            R.id.overview_disconnect_3h     -> description = resourceHelper.gs(R.string.disconnectpumpfor3h)
        }
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, resourceHelper.gs(R.string.confirm), description, Runnable {
                onClick(v)
            })
        }
        return true
    }

    fun onClick(v: View): Boolean {
        val profile = profileFunction.getProfile() ?: return true
        when (v.id) {
            R.id.overview_closeloop -> {
                sp.putString(R.string.key_aps_mode, "closed")
                rxBus.send(EventPreferenceChange(resourceHelper.gs(R.string.closedloop)))
            }
            R.id.overview_lgsloop -> {
                sp.putString(R.string.key_aps_mode, "lgs")
                rxBus.send(EventPreferenceChange(resourceHelper.gs(R.string.lowglucosesuspend)))
            }
            R.id.overview_openloop -> {
                sp.putString(R.string.key_aps_mode, "open")
                rxBus.send(EventPreferenceChange(resourceHelper.gs(R.string.lowglucosesuspend)))
            }
            R.id.overview_disable -> {
                aapsLogger.debug("USER ENTRY: LOOP DISABLED")
                loopPlugin.setPluginEnabled(PluginType.LOOP, false)
                loopPlugin.setFragmentVisible(PluginType.LOOP, false)
                configBuilderPlugin.storeSettings("DisablingLoop")
                rxBus.send(EventRefreshOverview("suspendmenu"))
                commandQueue.cancelTempBasal(true, object : Callback() {
                    override fun run() {
                        if (!result.success) {
                            ToastUtils.showToastInUiThread(context, resourceHelper.gs(R.string.tempbasaldeliveryerror))
                        }
                    }
                })
                loopPlugin.createOfflineEvent(24 * 60) // upload 24h, we don't know real duration
                return true
            }

            R.id.overview_enable -> {
                aapsLogger.debug("USER ENTRY: LOOP ENABLED")
                loopPlugin.setPluginEnabled(PluginType.LOOP, true)
                loopPlugin.setFragmentVisible(PluginType.LOOP, true)
                configBuilderPlugin.storeSettings("EnablingLoop")
                rxBus.send(EventRefreshOverview("suspendmenu"))
                loopPlugin.createOfflineEvent(0)
                return true
            }

            R.id.overview_resume, R.id.overview_reconnect -> {
                aapsLogger.debug("USER ENTRY: RESUME")
                loopPlugin.suspendTo(0L)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                commandQueue.cancelTempBasal(true, object : Callback() {
                    override fun run() {
                        if (!result.success) {
                            val i = Intent(context, ErrorHelperActivity::class.java)
                            i.putExtra("soundid", R.raw.boluserror)
                            i.putExtra("status", result.comment)
                            i.putExtra("title", resourceHelper.gs(R.string.tempbasaldeliveryerror))
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context?.startActivity(i)
                        }
                    }
                })
                sp.putBoolean(R.string.key_objectiveusereconnect, true)
                loopPlugin.createOfflineEvent(0)
                return true
            }

            R.id.overview_suspend_1h -> {
                aapsLogger.debug("USER ENTRY: SUSPEND 1h")
                loopPlugin.suspendLoop(60)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            R.id.overview_suspend_2h -> {
                aapsLogger.debug("USER ENTRY: SUSPEND 2h")
                loopPlugin.suspendLoop(120)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            R.id.overview_suspend_3h -> {
                aapsLogger.debug("USER ENTRY: SUSPEND 3h")
                loopPlugin.suspendLoop(180)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            R.id.overview_suspend_10h -> {
                aapsLogger.debug("USER ENTRY: SUSPEND 10h")
                loopPlugin.suspendLoop(600)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            R.id.overview_disconnect_15m -> {
                aapsLogger.debug("USER ENTRY: DISCONNECT 15m")
                loopPlugin.disconnectPump(15, profile)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            R.id.overview_disconnect_30m -> {
                aapsLogger.debug("USER ENTRY: DISCONNECT 30m")
                loopPlugin.disconnectPump(30, profile)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            R.id.overview_disconnect_1h -> {
                aapsLogger.debug("USER ENTRY: DISCONNECT 1h")
                loopPlugin.disconnectPump(60, profile)
                sp.putBoolean(R.string.key_objectiveusedisconnect, true)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            R.id.overview_disconnect_2h -> {
                aapsLogger.debug("USER ENTRY: DISCONNECT 2h")
                loopPlugin.disconnectPump(120, profile)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }

            R.id.overview_disconnect_3h -> {
                aapsLogger.debug("USER ENTRY: DISCONNECT 3h")
                loopPlugin.disconnectPump(180, profile)
                rxBus.send(EventRefreshOverview("suspendmenu"))
                return true
            }
        }
        return false
    }

    override fun show(manager: FragmentManager, tag: String?) {
        try {
            manager.beginTransaction().let {
                it.add(this, tag)
                it.commitAllowingStateLoss()
            }
        } catch (e: IllegalStateException) {
            aapsLogger.debug(e.localizedMessage)
        }
    }
}
