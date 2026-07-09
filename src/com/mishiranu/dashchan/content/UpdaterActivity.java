package com.mishiranu.dashchan.content;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import androidx.annotation.NonNull;
import chan.content.ChanManager;
import chan.util.DataFile;
import chan.util.StringUtils;
import com.mishiranu.dashchan.content.service.DownloadService;
import com.mishiranu.dashchan.ui.StateActivity;
import com.mishiranu.dashchan.util.IOUtils;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class UpdaterActivity extends StateActivity {
	private static final String EXTRA_FILES = "files";

	private static final String EXTRA_INDEX = "index";

	private static final String ACTION_INSTALL_STATUS =
			"com.mishiranu.dashchan.action.INSTALL_STATUS";

	private int index = 0;

	private List<String> getFiles() {
		return getIntent().getStringArrayListExtra(EXTRA_FILES);
	}

	private final BroadcastReceiver installStatusReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
			if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
				Intent confirmIntent = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
				if (confirmIntent != null) {
					startActivity(confirmIntent);
				} else {
					finish();
				}
			} else if (status == PackageInstaller.STATUS_SUCCESS) {
				index++;
				performInstallation();
			} else {
				finish();
			}
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		registerReceiver(installStatusReceiver, new IntentFilter(ACTION_INSTALL_STATUS),
				Context.RECEIVER_NOT_EXPORTED);
		if (savedInstanceState == null) {
			performInstallation();
		} else {
			index = savedInstanceState.getInt(EXTRA_INDEX);
		}
	}

	@Override
	protected void onDestroy() {
		unregisterReceiver(installStatusReceiver);
		super.onDestroy();
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle outState) {
		super.onSaveInstanceState(outState);
		outState.putInt(EXTRA_INDEX, index);
	}

	private void performInstallation() {
		List<String> files = getFiles();
		if (files != null && files.size() > index) {
			File file = FileProvider.getUpdatesFile(files.get(index));
			if (file == null) {
				index++;
				performInstallation();
			} else {
				new Thread(() -> commitInstallSession(file)).start();
			}
		} else {
			finish();
		}
	}

	private void commitInstallSession(File file) {
		PackageInstaller installer = getPackageManager().getPackageInstaller();
		int sessionId = -1;
		try {
			PackageInstaller.SessionParams params = new PackageInstaller
					.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
			sessionId = installer.createSession(params);
			try (PackageInstaller.Session session = installer.openSession(sessionId)) {
				try (OutputStream output = session.openWrite(file.getName(), 0, file.length());
						InputStream input = new FileInputStream(file)) {
					IOUtils.copyStream(input, output);
					session.fsync(output);
				}
				Intent statusIntent = new Intent(ACTION_INSTALL_STATUS).setPackage(getPackageName());
				PendingIntent pendingIntent = PendingIntent.getBroadcast(this, sessionId, statusIntent,
						PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
				session.commit(pendingIntent.getIntentSender());
			}
		} catch (IOException | RuntimeException e) {
			e.printStackTrace();
			if (sessionId >= 0) {
				try {
					installer.abandonSession(sessionId);
				} catch (RuntimeException abandonException) {
					// Ignore
				}
			}
			runOnUiThread(this::finish);
		}
	}

	private static Connection activeConnection;

	private static class Connection implements ServiceConnection, DownloadService.Callback {
		private final Context context;
		private final List<DownloadService.DownloadItem> downloadItems;
		private final HashMap<String, Boolean> status = new HashMap<>();

		public Connection(Context context, List<DownloadService.DownloadItem> downloadItems) {
			this.context = context;
			this.downloadItems = downloadItems;
			context.bindService(new Intent(context, DownloadService.class), this, BIND_AUTO_CREATE);
		}

		private DownloadService.Binder binder;

		@Override
		public void onServiceConnected(ComponentName componentName, IBinder binder) {
			this.binder = (DownloadService.Binder) binder;
			this.binder.register(this);
			this.binder.downloadDirect(DataFile.Target.UPDATES, null, true, downloadItems);
		}

		@Override
		public void onServiceDisconnected(ComponentName componentName) {
			if (this == activeConnection) {
				activeConnection = null;
				if (binder != null) {
					binder.unregister(this);
					binder = null;
				}
			}
		}

		private boolean finish() {
			if (this == activeConnection) {
				activeConnection = null;
				if (binder != null) {
					binder.unregister(this);
					binder = null;
					context.unbindService(this);
					return true;
				}
			}
			return false;
		}

		@Override
		public void onFinishDownloading(boolean success, DataFile.Target target, String path, String name) {
			if (target == DataFile.Target.UPDATES && StringUtils.isEmpty(path)) {
				status.put(name, success);
			}
			if (success) {
				boolean successAll = true;
				for (DownloadService.DownloadItem downloadItem : downloadItems) {
					Boolean status = this.status.get(downloadItem.name);
					if (status == null || !status) {
						successAll = false;
						break;
					}
				}
				if (successAll) {
					if (finish()) {
						ArrayList<String> files = new ArrayList<>(downloadItems.size());
						File directory = FileProvider.getUpdatesDirectory();
						for (DownloadService.DownloadItem downloadItem : downloadItems) {
							if (!new File(directory, downloadItem.name).exists()) {
								break;
							}
							files.add(downloadItem.name);
						}
						if (files.size() == downloadItems.size()) {
							context.startActivity(new Intent(context, UpdaterActivity.class)
									.putStringArrayListExtra(EXTRA_FILES, files)
									.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
						}
					}
				}
			}
		}

		@Override
		public void onCleanup() {
			finish();
		}

		public void cancel() {
			if (binder != null) {
				binder.unregister(this);
				binder = null;
				context.unbindService(this);
			}
		}
	}

	public static class Request {
		public final String extensionName;
		public final String versionName;
		public final Uri uri;
		public final byte[] sha256sum;
		public final ChanManager.Fingerprints checkFingerprints;

		public Request(String extensionName, String versionName, Uri uri,
				byte[] sha256sum, ChanManager.Fingerprints checkFingerprints) {
			this.extensionName = extensionName;
			this.versionName = versionName;
			this.uri = uri;
			this.sha256sum = sha256sum;
			this.checkFingerprints = checkFingerprints;
		}
	}

	public static void startUpdater(List<Request> requests) {
		DownloadService.DownloadItem clientDownloadItem = null;
		ArrayList<DownloadService.DownloadItem> downloadItems = new ArrayList<>();
		for (Request request : requests) {
			String name = request.extensionName + "-" + request.versionName + ".apk";
			DownloadService.DownloadItem downloadItem = new DownloadService.DownloadItem(null,
					request.uri, name, request.sha256sum, request.checkFingerprints);
			if (ChanManager.EXTENSION_NAME_CLIENT.equals(request.extensionName)) {
				clientDownloadItem = downloadItem;
			} else {
				downloadItems.add(downloadItem);
			}
		}
		if (clientDownloadItem != null) {
			downloadItems.add(clientDownloadItem);
		}
		if (activeConnection != null) {
			activeConnection.cancel();
		}
		activeConnection = new Connection(MainApplication.getInstance(), downloadItems);
	}
}
