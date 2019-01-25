package com.lody.virtual.server.am;

import android.Manifest;
import android.app.ActivityManager;
import android.app.IStopUserCallback;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;

import com.lody.virtual.client.IVClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.Constants;
import com.lody.virtual.client.env.SpecialComponentList;
import com.lody.virtual.client.ipc.ProviderCall;
import com.lody.virtual.client.ipc.VNotificationManager;
import com.lody.virtual.client.stub.StubManifest;
import com.lody.virtual.helper.collection.ArrayMap;
import com.lody.virtual.helper.collection.SparseArray;
import com.lody.virtual.helper.compat.ActivityManagerCompat;
import com.lody.virtual.helper.compat.ApplicationThreadCompat;
import com.lody.virtual.helper.compat.BundleCompat;
import com.lody.virtual.helper.compat.PermissionCompat;
import com.lody.virtual.helper.utils.ComponentUtils;
import com.lody.virtual.helper.utils.Singleton;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VBinder;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.AppTaskInfo;
import com.lody.virtual.remote.BadgerInfo;
import com.lody.virtual.remote.ClientConfig;
import com.lody.virtual.remote.IntentSenderData;
import com.lody.virtual.remote.VParceledListSlice;
import com.lody.virtual.server.bit64.V64BitHelper;
import com.lody.virtual.server.interfaces.IActivityManager;
import com.lody.virtual.server.pm.PackageCacheManager;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.VAppManagerService;
import com.lody.virtual.server.pm.VPackageManagerService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Lody
 */
public class VActivityManagerService extends IActivityManager.Stub {

    private static final Singleton<VActivityManagerService> sService = new Singleton<VActivityManagerService>() {
        @Override
        protected VActivityManagerService create() {
            return new VActivityManagerService();
        }
    };
    private static final String TAG = VActivityManagerService.class.getSimpleName();
    private final Object mProcessLock = new Object();
    private final List<ProcessRecord> mPidsSelfLocked = new ArrayList<>();
    private final ActivityStack mActivityStack = new ActivityStack(this);
    private final ProcessMap<ProcessRecord> mProcessNames = new ProcessMap<>();
    private final Map<IBinder, IntentSenderData> mIntentSenderMap = new HashMap<>();
    private NotificationManager nm = (NotificationManager) VirtualCore.get().getContext()
            .getSystemService(Context.NOTIFICATION_SERVICE);
    private final Map<String, Boolean> sIdeMap = new HashMap<>();
    private boolean mResult;

    public static VActivityManagerService get() {
        return sService.get();
    }


    @Override
    public int startActivity(Intent intent, ActivityInfo info, IBinder resultTo, Bundle options, String resultWho, int requestCode, int userId) {
        synchronized (this) {
            return mActivityStack.startActivityLocked(userId, intent, info, resultTo, options, resultWho, requestCode, VBinder.getCallingUid());
        }
    }

    @Override
    public boolean finishActivityAffinity(int userId, IBinder token) {
        synchronized (this) {
            return mActivityStack.finishActivityAffinity(userId, token);
        }
    }

    @Override
    public int startActivities(Intent[] intents, String[] resolvedTypes, IBinder token, Bundle options, int userId) {
        synchronized (this) {
            ActivityInfo[] infos = new ActivityInfo[intents.length];
            for (int i = 0; i < intents.length; i++) {
                ActivityInfo ai = VirtualCore.get().resolveActivityInfo(intents[i], userId);
                if (ai == null) {
                    return ActivityManagerCompat.START_INTENT_NOT_RESOLVED;
                }
                infos[i] = ai;
            }
            return mActivityStack.startActivitiesLocked(userId, intents, infos, resolvedTypes, token, options, VBinder.getCallingUid());
        }
    }


    @Override
    public int getSystemPid() {
        return Process.myPid();
    }

    @Override
    public int getSystemUid() {
        return Process.myUid();
    }

    @Override
    public void onActivityCreated(IBinder record, IBinder token, int taskId) {
        int pid = Binder.getCallingPid();
        ProcessRecord targetApp;
        synchronized (mProcessLock) {
            targetApp = findProcessLocked(pid);
        }
        if (targetApp != null) {
            mActivityStack.onActivityCreated(targetApp, token, taskId, (ActivityRecord) record);
        }
    }

    @Override
    public void onActivityResumed(int userId, IBinder token) {
        mActivityStack.onActivityResumed(userId, token);
    }

    @Override
    public boolean onActivityDestroyed(int userId, IBinder token) {
        ActivityRecord r = mActivityStack.onActivityDestroyed(userId, token);
        return r != null;
    }

    @Override
    public void onActivityFinish(int userId, IBinder token) {
        mActivityStack.onActivityFinish(userId, token);
    }

    @Override
    public AppTaskInfo getTaskInfo(int taskId) {
        return mActivityStack.getTaskInfo(taskId);
    }

    @Override
    public String getPackageForToken(int userId, IBinder token) {
        return mActivityStack.getPackageForToken(userId, token);
    }

    @Override
    public ComponentName getActivityClassForToken(int userId, IBinder token) {
        return mActivityStack.getActivityClassForToken(userId, token);
    }


    private void processDied(ProcessRecord record) {
        mServices.processDied(record);
        mActivityStack.processDied(record);
    }

    @Override
    public IBinder acquireProviderClient(int userId, ProviderInfo info) {
        String processName = info.processName;
        ProcessRecord r;
        synchronized (this) {
            r = startProcessIfNeedLocked(processName, userId, info.packageName, -1, VBinder.getCallingUid());
        }
        if (r != null) {
            try {
                return r.client.acquireProviderClient(info);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    @Override
    public void addOrUpdateIntentSender(IntentSenderData sender, int userId) {
        if (sender == null || sender.token == null) {
            return;
        }

        synchronized (mIntentSenderMap) {
            IntentSenderData data = mIntentSenderMap.get(sender.token);
            if (data == null) {
                mIntentSenderMap.put(sender.token, sender);
            } else {
                data.replace(sender);
            }
        }
    }

    @Override
    public void removeIntentSender(IBinder token) {
        if (token != null) {
            synchronized (mIntentSenderMap) {
                mIntentSenderMap.remove(token);
            }
        }
    }

    @Override
    public IntentSenderData getIntentSender(IBinder token) {
        if (token != null) {
            synchronized (mIntentSenderMap) {
                return mIntentSenderMap.get(token);
            }
        }
        return null;
    }

    @Override
    public ComponentName getCallingActivity(int userId, IBinder token) {
        return mActivityStack.getCallingActivity(userId, token);
    }

    @Override
    public String getCallingPackage(int userId, IBinder token) {
        return mActivityStack.getCallingPackage(userId, token);
    }


    @Override
    public VParceledListSlice<ActivityManager.RunningServiceInfo> getServices(int maxNum, int flags, int userId) {
        List<ActivityManager.RunningServiceInfo> infos = mServices.getServices(userId);
        return new VParceledListSlice<>(infos);
    }

    private void cancelNotification(int userId, int id, String pkg) {
        id = VNotificationManager.get().dealNotificationId(id, pkg, null, userId);
        String tag = VNotificationManager.get().dealNotificationTag(id, pkg, null, userId);
        nm.cancel(tag, id);
    }

    private void postNotification(int userId, int id, String pkg, Notification notification) {
        id = VNotificationManager.get().dealNotificationId(id, pkg, null, userId);
        String tag = VNotificationManager.get().dealNotificationTag(id, pkg, null, userId);
        VNotificationManager.get().addNotification(id, tag, pkg, userId);
        try {
            nm.notify(tag, id, notification);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void processRestarted(String packageName, String processName, int userId) {
        int callingVUid = VBinder.getCallingUid();
        int callingPid = VBinder.getCallingPid();
        synchronized (this) {
            ProcessRecord app;
            synchronized (mProcessLock) {
                app = findProcessLocked(callingPid);
            }
            if (app == null) {
                String stubProcessName = getProcessName(callingPid);
                if (stubProcessName == null) {
                    return;
                }
                int vpid = parseVPid(stubProcessName);
                if (vpid != -1) {
                    startProcessIfNeedLocked(processName, userId, packageName, vpid, callingVUid);
                }
            }
        }
    }

    private int parseVPid(String stubProcessName) {
        String prefix;
        if (stubProcessName == null) {
            return -1;
        } else if (stubProcessName.startsWith(StubManifest.PACKAGE_NAME_64BIT)) {
            prefix = StubManifest.PACKAGE_NAME_64BIT + ":p";
        } else if (stubProcessName.startsWith(StubManifest.PACKAGE_NAME)) {
            prefix = VirtualCore.get().getHostPkg() + ":p";
        } else {
            return -1;
        }
        if (stubProcessName.startsWith(prefix)) {
            try {
                return Integer.parseInt(stubProcessName.substring(prefix.length()));
            } catch (NumberFormatException e) {
                // ignore
            }
        }
        return -1;
    }


    private String getProcessName(int pid) {
        for (ActivityManager.RunningAppProcessInfo info : VirtualCore.get().getRunningAppProcessesEx()) {
            if (info.pid == pid) {
                return info.processName;
            }
        }
        return null;
    }


    private boolean attachClient(final ProcessRecord app, final IBinder clientBinder) {
        IVClient client = IVClient.Stub.asInterface(clientBinder);
        if (client == null) {
            app.kill();
            return false;
        }
        try {
            clientBinder.linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    clientBinder.unlinkToDeath(this, 0);
                    onProcessDied(app);
                }
            }, 0);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        app.client = client;
        try {
            app.appThread = ApplicationThreadCompat.asInterface(client.getAppThread());
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return true;
    }

    private void onProcessDied(ProcessRecord record) {
        synchronized (mProcessLock) {
            mProcessNames.remove(record.processName, record.vuid);
            mPidsSelfLocked.remove(record);
        }
        processDied(record);
    }

    @Override
    public int getFreeStubCount() {
        return StubManifest.STUB_COUNT - mPidsSelfLocked.size();
    }

    @Override
    public int checkPermission(boolean is64bit, String permission, int pid, int uid) {
        if (permission == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (Manifest.permission.ACCOUNT_MANAGER.equals(permission)) {
            return PackageManager.PERMISSION_GRANTED;
        }
        if ("android.permission.INTERACT_ACROSS_USERS".equals(permission) || "android.permission.INTERACT_ACROSS_USERS_FULL".equals(permission)) {
            return PackageManager.PERMISSION_DENIED;
        }
        if (uid == 0) {
            return PackageManager.PERMISSION_GRANTED;
        }
        return VPackageManagerService.get().checkUidPermission(is64bit, permission, uid);
    }

    @Override
    public ClientConfig initProcess(String packageName, String processName, int userId) {
        synchronized (this) {
            ProcessRecord r = startProcessIfNeedLocked(processName, userId, packageName, -1, VBinder.getCallingUid());
            if (r != null) {
                return r.getClientConfig();
            }
            return null;
        }
    }

    @Override
    public void appDoneExecuting(String packageName, int userId) {
        int pid = VBinder.getCallingPid();
        ProcessRecord r = findProcessLocked(pid);
        if (r != null) {
            r.pkgList.add(packageName);
        }
    }


    ProcessRecord startProcessIfNeedLocked(String processName, int userId, String packageName, int vpid, int callingUid) {
        runProcessGC();
        PackageSetting ps = PackageCacheManager.getSetting(packageName);
        ApplicationInfo info = VPackageManagerService.get().getApplicationInfo(packageName, 0, userId);
        if (ps == null || info == null) {
            return null;
        }
        if (!ps.isLaunched(userId)) {
            sendFirstLaunchBroadcast(ps, userId);
            ps.setLaunched(userId, true);
            VAppManagerService.get().savePersistenceData();
        }
        int vuid = VUserHandle.getUid(userId, ps.appId);
        boolean is64bit = ps.isRunOn64BitProcess();
        synchronized (mProcessLock) {
            ProcessRecord app = null;
            if (vpid == -1) {
                app = mProcessNames.get(processName, vuid);
                if (app != null) {
                    if (app.initLock != null) {
                        app.initLock.block();
                    }
                    if (app.client != null) {
                        return app;
                    }
                }
                VLog.w(TAG, "start new process : " + processName);
                vpid = queryFreeStubProcess(is64bit);
            }
            if (vpid == -1) {
                VLog.e(TAG, "Unable to query free stub for : " + processName);
                return null;
            }
            if (app != null) {
                VLog.w(TAG, "remove invalid process record: " + app.processName);
                mProcessNames.remove(app.processName, app.vuid);
                mPidsSelfLocked.remove(app);
            }
            app = new ProcessRecord(info, processName, vuid, vpid, callingUid, is64bit);
            mProcessNames.put(app.processName, app.vuid, app);
            mPidsSelfLocked.add(app);
            if (initProcess(app)) {
                return app;
            } else {
                return null;
            }
        }
    }


    private void runProcessGC() {
        if (VActivityManagerService.get().getFreeStubCount() < 3) {
            // run GC
            killAllApps();
        }
    }

    private void sendFirstLaunchBroadcast(PackageSetting ps, int userId) {
        Intent intent = new Intent(Intent.ACTION_PACKAGE_FIRST_LAUNCH, Uri.fromParts("package", ps.packageName, null));
        intent.setPackage(ps.packageName);
        intent.putExtra(Intent.EXTRA_UID, VUserHandle.getUid(ps.appId, userId));
        intent.putExtra("android.intent.extra.user_handle", userId);
        sendBroadcastAsUser(intent, new VUserHandle(userId));
    }


    @Override
    public int getUidByPid(int pid) {
        if (pid == Process.myPid()) {
            return Constants.OUTSIDE_APP_UID;
        }
        boolean isClientPid = false;
        if (pid == 0) {
            pid = VBinder.getCallingPid();
            isClientPid = true;
        }
        synchronized (mProcessLock) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                if (isClientPid) {
                    return r.callingVUid;
                } else {
                    return r.vuid;
                }
            }
        }
        if (pid == Process.myPid()) {
            return Constants.OUTSIDE_APP_UID;
        }
        return Constants.OUTSIDE_APP_UID;
    }

    private void startRequestPermissions(boolean is64bit, String[] permissions,
                                         final ConditionVariable permissionLock) {

        PermissionCompat.startRequestPermissions(VirtualCore.get().getContext(), is64bit, permissions, new PermissionCompat.CallBack() {
            @Override
            public boolean onResult(int requestCode, String[] permissions, int[] grantResults) {
                try {
                    mResult = PermissionCompat.isRequestGranted(grantResults);
                } finally {
                    permissionLock.open();
                }
                return mResult;
            }
        });
    }


    private boolean initProcess(ProcessRecord app) {
        try {
            requestPermissionIfNeed(app);
            Bundle extras = new Bundle();
            extras.putParcelable("_VA_|_client_config_", app.getClientConfig());
            Bundle res = ProviderCall.callSafely(app.getProviderAuthority(), "_VA_|_init_process_", null, extras);
            if (res == null) {
                return false;
            }
            app.pid = res.getInt("_VA_|_pid_");
            IBinder clientBinder = BundleCompat.getBinder(res, "_VA_|_client_");
            return attachClient(app, clientBinder);
        } finally {
            app.initLock.open();
            app.initLock = null;
        }
    }

    private void requestPermissionIfNeed(ProcessRecord app) {
        if (PermissionCompat.isCheckPermissionRequired(app.info.targetSdkVersion)) {
            String[] permissions = VPackageManagerService.get().getDangrousPermissions(app.info.packageName);
            if (!PermissionCompat.checkPermissions(permissions, app.is64bit)) {
                final ConditionVariable permissionLock = new ConditionVariable();
                startRequestPermissions(app.is64bit, permissions, permissionLock);
                permissionLock.block();
            }
        }
    }

    public int queryFreeStubProcess(boolean is64bit) {
        synchronized (mProcessLock) {
            for (int vpid = 0; vpid < StubManifest.STUB_COUNT; vpid++) {
                int N = mPidsSelfLocked.size();
                boolean using = false;
                while (N-- > 0) {
                    ProcessRecord r = mPidsSelfLocked.get(N);
                    if (r.vpid == vpid && r.is64bit == is64bit) {
                        using = true;
                        break;
                    }
                }
                if (using) {
                    continue;
                }
                return vpid;
            }
        }
        return -1;
    }

    @Override
    public boolean isAppProcess(String processName) {
        return parseVPid(processName) != -1;
    }

    @Override
    public boolean isAppPid(int pid) {
        synchronized (mProcessLock) {
            return findProcessLocked(pid) != null;
        }
    }

    @Override
    public String getAppProcessName(int pid) {
        synchronized (mProcessLock) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                return r.processName;
            }
        }
        return null;
    }

    @Override
    public List<String> getProcessPkgList(int pid) {
        synchronized (mProcessLock) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                return new ArrayList<>(r.pkgList);
            }
        }
        return Collections.emptyList();
    }

    @Override
    public void killAllApps() {
        synchronized (mProcessLock) {
            for (int i = 0; i < mPidsSelfLocked.size(); i++) {
                ProcessRecord r = mPidsSelfLocked.get(i);
                r.kill();
            }
        }
    }

    @Override
    public void killAppByPkg(final String pkg, int userId) {
        synchronized (mProcessLock) {
            ArrayMap<String, SparseArray<ProcessRecord>> map = mProcessNames.getMap();
            int N = map.size();
            while (N-- > 0) {
                SparseArray<ProcessRecord> uids = map.valueAt(N);
                if (uids != null) {
                    for (int i = 0; i < uids.size(); i++) {
                        ProcessRecord r = uids.valueAt(i);
                        if (userId != VUserHandle.USER_ALL) {
                            if (r.userId != userId) {
                                continue;
                            }
                        }
                        if (r.pkgList.contains(pkg)) {
                            r.kill();
                        }
                    }
                }
            }
        }
    }

    @Override
    public boolean isAppRunning(String packageName, int userId, boolean foreground) {
        boolean running = false;
        synchronized (mProcessLock) {
            int N = mPidsSelfLocked.size();
            while (N-- > 0) {
                ProcessRecord r = mPidsSelfLocked.get(N);
                if (r.userId != userId) {
                    continue;
                }
                if (!r.info.packageName.equals(packageName)) {
                    continue;
                }
                if (foreground) {
                    if (!r.info.processName.equals(packageName)) {
                        continue;
                    }
                }
                try {
                    running = r.client.isAppRunning();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                break;
            }
            return running;
        }
    }

    @Override
    public void killApplicationProcess(final String processName, int uid) {
        synchronized (mProcessLock) {
            ProcessRecord r = mProcessNames.get(processName, uid);
            if (r != null) {
                if (r.is64bit) {
                    V64BitHelper.forceStop64(r.pid);
                } else {
                    r.kill();
                }
            }
        }
    }

    @Override
    public void dump() {

    }

    @Override
    public String getInitialPackage(int pid) {
        synchronized (mProcessLock) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                return r.info.packageName;
            }
            return null;
        }
    }


    /**
     * Should guard by {@link VActivityManagerService#mPidsSelfLocked}
     *
     * @param pid pid
     */
    public ProcessRecord findProcessLocked(int pid) {
        for (ProcessRecord r : mPidsSelfLocked) {
            if (r.pid == pid) {
                return r;
            }
        }
        return null;
    }

    /**
     * Should guard by {@link VActivityManagerService#mProcessNames}
     *
     * @param uid vuid
     */
    public ProcessRecord findProcessLocked(String processName, int uid) {
        return mProcessNames.get(processName, uid);
    }

    public int stopUser(int userHandle, IStopUserCallback.Stub stub) {
        synchronized (mProcessLock) {
            int N = mPidsSelfLocked.size();
            while (N-- > 0) {
                ProcessRecord r = mPidsSelfLocked.get(N);
                if (r.userId == userHandle) {
                    r.kill();
                }
            }
        }
        try {
            stub.userStopped(userHandle);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        return 0;
    }

    public void sendOrderedBroadcastAsUser(Intent intent, VUserHandle user, String receiverPermission,
                                           BroadcastReceiver resultReceiver, Handler scheduler, int initialCode,
                                           String initialData, Bundle initialExtras) {
        Context context = VirtualCore.get().getContext();
        if (user != null) {
            intent.putExtra("_VA_|_user_id_", user.getIdentifier());
        }
        context.sendOrderedBroadcast(intent, null/* permission */, resultReceiver, scheduler, initialCode, initialData,
                initialExtras);
    }

    public void sendBroadcastAsUser(Intent intent, VUserHandle user) {
        SpecialComponentList.protectIntent(intent);
        Context context = VirtualCore.get().getContext();
        if (user != null) {
            intent.putExtra("_VA_|_user_id_", user.getIdentifier());
        }
        context.sendBroadcast(intent);
    }

    public boolean bindServiceAsUser(Intent service, ServiceConnection connection, int flags, VUserHandle user) {
        service = new Intent(service);
        if (user != null) {
            service.putExtra("_VA_|_user_id_", user.getIdentifier());
        }
        return VirtualCore.get().getContext().bindService(service, connection, flags);
    }

    public void sendBroadcastAsUser(Intent intent, VUserHandle user, String permission) {
        SpecialComponentList.protectIntent(intent);
        Context context = VirtualCore.get().getContext();
        if (user != null) {
            intent.putExtra("_VA_|_user_id_", user.getIdentifier());
        }
        context.sendBroadcast(intent);
    }


    @Override
    public void notifyBadgerChange(BadgerInfo info) {
        Intent intent = new Intent(Constants.ACTION_BADGER_CHANGE);
        intent.putExtra("userId", info.userId);
        intent.putExtra("packageName", info.packageName);
        intent.putExtra("badgerCount", info.badgerCount);
        VirtualCore.get().getContext().sendBroadcast(intent);
    }

    @Override
    public int getCallingUidByPid(int pid) {
        synchronized (mProcessLock) {
            ProcessRecord r = findProcessLocked(pid);
            if (r != null) {
                return r.getCallingVUid();
            }
        }
        return -1;
    }

    @Override
    public void setAppInactive(String packageName, boolean idle, int userId) {
        synchronized (sIdeMap) {
            sIdeMap.put(packageName + "@" + userId, idle);
        }
    }

    @Override
    public boolean isAppInactive(String packageName, int userId) {
        synchronized (sIdeMap) {
            Boolean idle = sIdeMap.get(packageName + "@" + userId);
            return idle != null && !idle;
        }
    }

    private final ActiveServices mServices = new ActiveServices(this);

    @Override
    public ComponentName startService(int userId, Intent service) {
        synchronized (mServices) {
            return mServices.startService(userId, service);
        }
    }

    @Override
    public void stopService(int userId, ServiceInfo serviceInfo) {
        synchronized (mServices) {
            int appId = VUserHandle.getAppId(serviceInfo.applicationInfo.uid);
            int uid = VUserHandle.getUid(userId, appId);
            ProcessRecord r = findProcessLocked(serviceInfo.processName, uid);
            if (r != null) {
                try {
                    r.client.stopService(ComponentUtils.toComponentName(serviceInfo));
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void unbindService(int userId, IBinder token) {
        synchronized (mServices) {
            mServices.unbindService(userId, token);
        }
    }

    @Override
    public Intent bindService(int userId, Intent intent, ServiceInfo serviceInfo, IBinder binder, int flags) {
        synchronized (mServices) {
            return mServices.bindService(userId, intent, serviceInfo, binder, flags);
        }
    }

    @Override
    public void onServiceStartCommand(int userId, int startId, ServiceInfo serviceInfo, Intent intent) {
        synchronized (mServices) {
            mServices.onStartCommand(userId, startId, serviceInfo, intent);
        }
    }

    @Override
    public int onServiceStop(int userId, ComponentName component, int targetStartId) {
        synchronized (mServices) {
            return mServices.stopService(userId, component, targetStartId);
        }
    }

    @Override
    public void onServiceDestroyed(int userId, ComponentName component) {
        synchronized (mServices) {
            mServices.onDestroy(userId, component);
        }
    }

    @Override
    public int onServiceUnBind(int userId, ComponentName component) {
        synchronized (mServices) {
            return mServices.onUnbind(userId, component);
        }
    }

    @Override
    public void handleDownloadCompleteIntent(Intent intent) {
        intent.setPackage(null);
        intent.setComponent(null);
        Intent send = ComponentUtils.redirectBroadcastIntent(intent, VUserHandle.USER_ALL);
        VirtualCore.get().getContext().sendBroadcast(send);
    }


    public void beforeProcessKilled(ProcessRecord processRecord) {
        // EMPTY
    }
}