package com.limpoxe.fairy.manager;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;

import com.limpoxe.fairy.content.PluginDescriptor;
import com.limpoxe.fairy.core.FairyGlobal;
import com.limpoxe.fairy.core.PluginCreator;
import com.limpoxe.fairy.core.PluginLauncher;
import com.limpoxe.fairy.core.localservice.LocalServiceManager;
import com.limpoxe.fairy.util.FileUtil;
import com.limpoxe.fairy.util.LogUtil;
import com.limpoxe.fairy.util.PackageVerifyer;
import com.limpoxe.fairy.util.ProcessUtil;
import com.limpoxe.fairy.util.RefInvoker;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

class PluginManagerService {

	private static final String ENABLED_KEY = "plugins.list";
	private static final String DISABLED_KEY = "plugins.pending";

	private Object mLock = new Object();
	private final Hashtable<String, PluginDescriptor> mEnabledPlugins = new Hashtable<String, PluginDescriptor>();
	private final Hashtable<String, PluginDescriptor> mDisabledPlugins = new Hashtable<String, PluginDescriptor>();

	PluginManagerService() {
		if (FairyGlobal.isInited()) {//防止集成了插件框架但是没有调用init导致app起不来
			if (!ProcessUtil.isPluginProcess()) {
				throw new IllegalAccessError("本类仅在插件进程使用");
			}
		} else {
			LogUtil.e("插件框架未初始化！");
			LogUtil.printStackTrace();
		}
	}

	/**
	 * 插件的安装目录, 插件apk将来会被放在这个目录下面
	 */
	private static String genInstallPath(String pluginId, String pluginVersoin) {
		if (pluginId.indexOf(File.separatorChar) >= 0 || pluginVersoin.indexOf(File.separatorChar) >= 0) {
			throw new IllegalArgumentException("path contains a path separator");
		}
		return  getPluginRootDir() + "/" + pluginId + "/" + pluginVersoin + "/base-1.apk";
	}

	private static String getPluginRootDir() {
		return FairyGlobal.getHostApplication().getDir("plugin_dir", Context.MODE_PRIVATE).getAbsolutePath();
	}

	@SuppressWarnings("unchecked")
	void loadEnabledPlugins() {
		synchronized (mLock) {
			if (mEnabledPlugins.size() == 0) {
				long t1 = System.currentTimeMillis();

				try {
					Hashtable<String, PluginDescriptor> installedPlugin = readPlugins(ENABLED_KEY);
					if (installedPlugin != null) {
						mEnabledPlugins.putAll(installedPlugin);
					}

					//把pending合并到install
					Hashtable<String, PluginDescriptor> pendingPlugin = readPlugins(DISABLED_KEY);
					if (pendingPlugin != null) {
						Iterator<Map.Entry<String, PluginDescriptor>> itr = pendingPlugin.entrySet().iterator();
						while (itr.hasNext()) {
							Map.Entry<String, PluginDescriptor> entry = itr.next();
							//删除旧版
							remove(entry.getKey());
						}

						//保存新版
						mEnabledPlugins.putAll(pendingPlugin);
						savePlugins(ENABLED_KEY, mEnabledPlugins);

						//清除pending
						getSharedPreference().edit().remove(DISABLED_KEY).commit();
					}
				} catch (Exception e) {
					LogUtil.printException("load plugins fail", e);
				}

				long t2 = System.currentTimeMillis();
				LogUtil.i("加载所有插件列表, 耗时 : " + (t2 - t1));
			}
		}
	}

	private boolean addOrReplace(PluginDescriptor pluginDescriptor) {
		mEnabledPlugins.put(pluginDescriptor.getPackageName(), pluginDescriptor);
        boolean isSaveSuccess = savePlugins(ENABLED_KEY, mEnabledPlugins);
        if (!isSaveSuccess) {
            mEnabledPlugins.remove(pluginDescriptor.getPackageName());
        }
        return isSaveSuccess;
	}

	private boolean pending(PluginDescriptor pluginDescriptor) {
		mDisabledPlugins.put(pluginDescriptor.getPackageName(), pluginDescriptor);
		return savePlugins(DISABLED_KEY, mDisabledPlugins);
	}

	boolean removeAll() {
		synchronized (mLock) {
			Iterator<Map.Entry<String, PluginDescriptor>> itr = mEnabledPlugins.entrySet().iterator();
			while(itr.hasNext()) {
				Map.Entry<String, PluginDescriptor> entry = itr.next();
				PluginLauncher.instance().stopPlugin(entry.getKey(), entry.getValue());
			}

			mEnabledPlugins.clear();
			boolean isSuccess = savePlugins(ENABLED_KEY, mEnabledPlugins);

			FileUtil.deleteAll(new File(getPluginRootDir()));

			return isSuccess;
		}
	}

	int remove(String pluginId) {
		synchronized (mLock) {
			PluginDescriptor old = mEnabledPlugins.get(pluginId);

			boolean result = false;

			if (old != null) {
				PluginLauncher.instance().stopPlugin(pluginId, old);
				LogUtil.e("remove records and files...", pluginId);
				mEnabledPlugins.remove(pluginId);
				result = savePlugins(ENABLED_KEY, mEnabledPlugins);
				boolean deleteSuccess = FileUtil.deleteAll(new File(old.getInstalledPath()).getParentFile());
				LogUtil.e("remove done", result, deleteSuccess, old.getInstalledPath(), old.getPackageName());
				if (deleteSuccess) {
					return PluginManagerHelper.REMOVE_SUCCESS;
				} else {
					return PluginManagerHelper.REMOVE_FAIL;
				}
			} else {
				LogUtil.e("插件未安装", pluginId);
				return PluginManagerHelper.REMOVE_FAIL_PLUGIN_NOT_EXIST;
			}
		}
	}

	Collection<PluginDescriptor> getPlugins() {
		return mEnabledPlugins.values();
	}

	/**
	 * for Fragment
	 *
	 * @param clazzId
	 * @return
	 */
	PluginDescriptor getPluginDescriptorByFragmenetId(String clazzId) {
		Iterator<PluginDescriptor> itr = mEnabledPlugins.values().iterator();
		while (itr.hasNext()) {
			PluginDescriptor descriptor = itr.next();
			if (descriptor.containsFragment(clazzId)) {
				return descriptor;
			}
		}
		return null;
	}

	PluginDescriptor getPluginDescriptorByPluginId(String pluginId) {
		PluginDescriptor pluginDescriptor = mEnabledPlugins.get(pluginId);
		if (pluginDescriptor != null && pluginDescriptor.isEnabled()) {
			return pluginDescriptor;
		}
		return null;
	}

	PluginDescriptor getPluginDescriptorByClassName(String clazzName) {
		Iterator<PluginDescriptor> itr = mEnabledPlugins.values().iterator();
		while (itr.hasNext()) {
			PluginDescriptor descriptor = itr.next();
			if (descriptor.containsName(clazzName)) {
				return descriptor;
			}
		}
		return null;
	}

	/**
	 * 安装一个插件
	 *
	 * @param srcPluginFile
	 * @return
	 */
	InstallResult installPlugin(String srcPluginFile) {
		synchronized (mLock) {
			LogUtil.w("开始安装插件", srcPluginFile);
			long startAt = System.currentTimeMillis();
			if (TextUtils.isEmpty(srcPluginFile) || !FileUtil.checkPathSafe(srcPluginFile)) {
				return new InstallResult(PluginManagerHelper.SRC_FILE_NOT_FOUND);
			}

			File srcFile = new File(srcPluginFile);
			if (!srcFile.exists() || !srcFile.isFile()) {
				return new InstallResult(PluginManagerHelper.SRC_FILE_NOT_FOUND);
			}

			try {
				//解析相对路径，得到真实绝对路径
				srcPluginFile = srcFile.getCanonicalPath();
			} catch (IOException e) {
				LogUtil.printException("PluginManagerService.installPlugin", e);
				return new InstallResult(PluginManagerHelper.INSTALL_FAIL);
			}

			// 先将apk复制到宿主程序私有目录，防止在安装过程中文件被篡改
			if (!srcPluginFile.startsWith(FairyGlobal.getHostApplication().getCacheDir().getAbsolutePath())) {
				String tempFilePath = FairyGlobal.getHostApplication().getCacheDir().getAbsolutePath()
					+ File.separator + System.currentTimeMillis() + "_" + srcFile.getName();
				if (FileUtil.copyFile(srcPluginFile, tempFilePath)) {
					srcPluginFile = tempFilePath;
				} else {
					LogUtil.e("复制插件文件失败", srcPluginFile, tempFilePath);
					new File(tempFilePath).delete();
					return new InstallResult(PluginManagerHelper.COPY_FILE_FAIL);
				}
			}

			// 解析Manifest，获得插件详情
			final PluginDescriptor pluginDescriptor = PluginManifestParser.parseManifest(srcPluginFile);
			if (pluginDescriptor == null || TextUtils.isEmpty(pluginDescriptor.getPackageName())) {
				LogUtil.e("解析插件Manifest文件失败", srcPluginFile);
				new File(srcPluginFile).delete();
				return new InstallResult(PluginManagerHelper.PARSE_MANIFEST_FAIL);
			}

			//判断插件适用系统版本
			if (pluginDescriptor.getMinSdkVersion() != null && Build.VERSION.SDK_INT < Integer.valueOf(pluginDescriptor.getMinSdkVersion()))  {
				LogUtil.e("当前系统版本过低, 不支持此插件", "系统:" + Build.VERSION.SDK_INT, "插件:" + pluginDescriptor.getMinSdkVersion(), pluginDescriptor.getPackageName());
				new File(srcPluginFile).delete();
				return new InstallResult(PluginManagerHelper.MIN_API_NOT_SUPPORTED, pluginDescriptor.getPackageName(), pluginDescriptor.getVersion());
			}

			// 验证插件APK签名，如果被篡改过，将获取不到证书
			// 之所以把验证签名步骤在放在验证适用系统版本之后，
			// 是因为不同的minSdkVersion在签名时使用的sha算法长度不同，
			// 也即高版本的minSdkVersion的插件，即使签名没有被篡改过，在低版本的系统中仍然会校验失败
			// 所以先校验minSdkVersion，再校验签名
			//sApplication.getPackageManager().getPackageArchiveInfo(srcPluginFile, PackageManager.GET_SIGNATURES);
			Signature[] pluginSignatures = PackageVerifyer.collectCertificates(srcPluginFile, false);
			boolean isDebugable = (0 != (FairyGlobal.getHostApplication().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));
			if (pluginSignatures == null) {
				LogUtil.e("插件签名验证失败", srcPluginFile);
				new File(srcPluginFile).delete();
				return new InstallResult(PluginManagerHelper.SIGNATURES_INVALIDATE);
			}

			//可选步骤，验证插件APK证书是否和宿主程序证书相同。
			//证书中存放的是公钥和算法信息，而公钥和私钥是1对1的
			//公钥相同意味着是同一个作者发布的程序
			if (FairyGlobal.isNeedVerifyPlugin() && !isDebugable) {
				Signature[] mainSignatures = null;
				try {
					PackageInfo pkgInfo = FairyGlobal.getHostApplication().getPackageManager().getPackageInfo(FairyGlobal.getHostApplication().getPackageName(), PackageManager.GET_SIGNATURES);
					mainSignatures = pkgInfo.signatures;
				} catch (PackageManager.NameNotFoundException e) {
					LogUtil.printException("PluginManagerService.installPlugin", e);
				}
				if (!PackageVerifyer.isSignaturesSame(mainSignatures, pluginSignatures)) {
					LogUtil.e("插件证书和宿主证书不一致", srcPluginFile);
					new File(srcPluginFile).delete();
					return new InstallResult(PluginManagerHelper.VERIFY_SIGNATURES_FAIL);
				}
			}

			// 检查当前宿主版本是否匹配此非独立插件需要的版本
			if (!PackageVerifyer.isCompatibleWithHost(pluginDescriptor)) {
				//不满足要求，不可安装此插件
				new File(srcPluginFile).delete();
				return new InstallResult(PluginManagerHelper.HOST_VERSION_NOT_SUPPORT_CURRENT_PLUGIN, pluginDescriptor.getPackageName(), pluginDescriptor.getVersion());
			}

			// 检查插件是否已经存在,若存在删除旧的
			PluginDescriptor oldPluginDescriptor = getPluginDescriptorByPluginId(pluginDescriptor.getPackageName());
			boolean isHotUpdate = false;
			if (oldPluginDescriptor != null) {
				LogUtil.d("已安装过，安装路径为", oldPluginDescriptor.getInstalledPath(), oldPluginDescriptor.getVersion(), pluginDescriptor.getVersion());

				//检查插件是否已经加载
				if (PluginLauncher.instance().isRunning(oldPluginDescriptor.getPackageName())) {
					if (!oldPluginDescriptor.getVersion().equals(pluginDescriptor.getVersion())) {
						LogUtil.w("旧版插件已经加载， 且新版插件和旧版插件版本不同，直接删除旧版，进行热更新");
						isHotUpdate = true;
						remove(oldPluginDescriptor.getPackageName());
					} else {
						LogUtil.e("旧版插件已经加载， 且新版插件和旧版插件版本相同，拒绝安装");
						new File(srcPluginFile).delete();
						return new InstallResult(PluginManagerHelper.FAIL_BECAUSE_SAME_VER_HAS_LOADED, pluginDescriptor.getPackageName(), pluginDescriptor.getVersion());
					}
				} else {
					LogUtil.v("旧版插件还未加载，忽略版本，直接删除旧版，尝试安装新版");
					remove(oldPluginDescriptor.getPackageName());
				}
			}

			// 复制插件到插件目录
			String destApkPath = genInstallPath(pluginDescriptor.getPackageName(), pluginDescriptor.getVersion());
			boolean isCopySuccess = FileUtil.copyFile(srcPluginFile, destApkPath);

			if (!isCopySuccess) {

				LogUtil.e("复制插件到安装目录失败", srcPluginFile);
				//删掉临时文件
				new File(srcPluginFile).delete();
				return new InstallResult(PluginManagerHelper.COPY_FILE_FAIL, pluginDescriptor.getPackageName(), pluginDescriptor.getVersion());
			} else {

				//第5步，先解压so到临时目录，再从临时目录复制到插件so目录。 在构造插件Dexclassloader的时候，会使用这个so目录作为参数
				File apkParent = new File(destApkPath).getParentFile();
				File tempSoDir = new File(apkParent, "temp");
				Set<String> soList = FileUtil.unZipSo(srcPluginFile, tempSoDir);
				if (soList != null) {//TODO soList插件中所有so的名字列表，如果插件中不同cpu架构下的so个数不相等可能会复制不匹配的so
					ArrayList<String> abiList = getSupportedAbis();
					for (String soName : soList) {
						FileUtil.copySo(tempSoDir, soName, apkParent.getAbsolutePath(), abiList);
					}
					//删掉临时文件
					FileUtil.deleteAll(tempSoDir);
				}

				//try {
				//ArrayList<String> multiDexFiles = PluginMultiDexExtractor.performExtractions(new File(destApkPath), new File(apkParent, "secondDexes"));
				//pluginDescriptor.setMuliDexList(multiDexFiles);
				//} catch (IOException e) {
				//	e.printStackTrace();
				//}

				//万事具备 添加到已安装插件列表
				pluginDescriptor.setInstalledPath(destApkPath);
				pluginDescriptor.setInstallationTime(System.currentTimeMillis());
				PackageInfo packageInfo = pluginDescriptor.getPackageInfo(PackageManager.GET_GIDS);
				if (packageInfo != null) {
					pluginDescriptor.setApplicationTheme(packageInfo.applicationInfo.theme);
					pluginDescriptor.setApplicationIcon(packageInfo.applicationInfo.icon);
					pluginDescriptor.setApplicationLogo(packageInfo.applicationInfo.logo);
				}

				boolean isInstallSuccess = addOrReplace(pluginDescriptor);

				//删掉临时文件
				new File(srcPluginFile).delete();

				if (!isInstallSuccess) {
					LogUtil.e("安装插件失败", srcPluginFile);

					new File(destApkPath).delete();

					return new InstallResult(PluginManagerHelper.INSTALL_FAIL, pluginDescriptor.getPackageName(), pluginDescriptor.getVersion());
				} else {
					//通过创建classloader来触发dexopt，但不加载
					LogUtil.d("正在进行DEXOPT...", pluginDescriptor.getInstalledPath());
					//ActivityThread.getPackageManager().performDexOptIfNeeded()
					FileUtil.deleteAll(new File(apkParent, "dalvik-cache"));
					ClassLoader cl = PluginCreator.createPluginClassLoader(
						pluginDescriptor.getPackageName(),
						pluginDescriptor.getInstalledPath(),
						pluginDescriptor.isStandalone(),
						null,
						null);
					try {
						cl.loadClass(Object.class.getName());
						cl = null;
					} catch (ClassNotFoundException e) {
						LogUtil.printException("PluginManagerService.installPlugin", e);
					}
					LogUtil.d("DEXOPT完毕");

					LogUtil.d("注册localService");
					LocalServiceManager.registerService(pluginDescriptor);

					long endAt = System.currentTimeMillis();
					LogUtil.w("插件安装成功", pluginDescriptor.getPackageName(), "耗时 : " + (endAt - startAt));

					LogUtil.v("安装路径", pluginDescriptor.getInstalledPath());

					//打印一下目录结构
					if (isDebugable) {
						FileUtil.printAll(new File(FairyGlobal.getHostApplication().getApplicationInfo().dataDir));
					}

					//自启动，安装时就启动
					if (pluginDescriptor.getAutoStart() || isHotUpdate) {
						LogUtil.w("wakeup", pluginDescriptor.getPackageName());
						PluginManagerHelper.wakeup(pluginDescriptor.getPackageName());
					}

					return new InstallResult(PluginManagerHelper.SUCCESS, pluginDescriptor.getPackageName(), pluginDescriptor.getVersion());
				}
			}
		}
	}

	private static ArrayList<String> getSupportedAbis() {

		ArrayList<String> abiList = new ArrayList<>();

		String defaultAbi = (String) RefInvoker.getField(FairyGlobal.getHostApplication().getApplicationInfo(), ApplicationInfo.class, "primaryCpuAbi");
		abiList.add(defaultAbi);

		if (Build.VERSION.SDK_INT >= 21) {
			String[] abis = Build.SUPPORTED_ABIS;
			if (abis != null) {
				for (String abi: abis) {
					abiList.add(abi);
				}
			}
		} else {
			abiList.add(Build.CPU_ABI);
			abiList.add(Build.CPU_ABI2);
			abiList.add("armeabi");
		}
		return abiList;
	}

	private static SharedPreferences getSharedPreference() {
		SharedPreferences sp = FairyGlobal.getHostApplication().getSharedPreferences("plugins.installed",
				Build.VERSION.SDK_INT < 11 ? Context.MODE_PRIVATE : Context.MODE_PRIVATE | 0x0004);
		return sp;
	}

	private boolean savePlugins(String key, Hashtable<String, PluginDescriptor> plugins) {

		ObjectOutputStream objectOutputStream = null;
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		try {
			objectOutputStream = new ObjectOutputStream(byteArrayOutputStream);
			objectOutputStream.writeObject(plugins);
			objectOutputStream.flush();

			byte[] data = byteArrayOutputStream.toByteArray();
			String list = Base64.encodeToString(data, Base64.DEFAULT);

			getSharedPreference().edit().putString(key, list).commit();
			return true;
		} catch (Exception e) {
			LogUtil.printException("PluginManagerService.savePlugins", e);
		} finally {
			if (objectOutputStream != null) {
				try {
					objectOutputStream.close();
				} catch (IOException e) {
					LogUtil.printException("PluginManagerService.savePlugins", e);
				}
			}
			if (byteArrayOutputStream != null) {
				try {
					byteArrayOutputStream.close();
				} catch (IOException e) {
					LogUtil.printException("PluginManagerService.savePlugins", e);
				}
			}
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	private Hashtable<String, PluginDescriptor> readPlugins(String key) {
		String list = getSharedPreference().getString(key, "");
		Serializable object = null;
		if (!TextUtils.isEmpty(list)) {
			ByteArrayInputStream byteArrayInputStream = null;
			ObjectInputStream objectInputStream = null;
			try {
				byteArrayInputStream = new ByteArrayInputStream(Base64.decode(list, Base64.DEFAULT));
				objectInputStream = new ObjectInputStream(byteArrayInputStream);
				object = (Serializable) objectInputStream.readObject();
			} catch (Exception e) {
				LogUtil.printException("PluginManagerService.readPlugins", e);
			} finally {
				if (objectInputStream != null) {
					try {
						objectInputStream.close();
					} catch (IOException e) {
						LogUtil.printException("PluginManagerService.readPlugins", e);
					}
				}
				if (byteArrayInputStream != null) {
					try {
						byteArrayInputStream.close();
					} catch (IOException e) {
						LogUtil.printException("PluginManagerService.readPlugins", e);
					}
				}
			}
		}

		return (Hashtable<String, PluginDescriptor>) object;
	}

}