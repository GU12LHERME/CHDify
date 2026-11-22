// SPDX-License-Identifier: BSD-3-Clause
// Ponte JNI entre o app (Kotlin) e o motor CHD.

#include "chd_engine.h"
#include "disc_probe.h"

#include <android/log.h>
#include <jni.h>

#include <atomic>
#include <mutex>
#include <string>
#include <vector>

#define LOG_TAG "ConversorCHD"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace chdconv;

namespace {

std::atomic<bool> g_cancel{ false };

std::string to_std(JNIEnv *env, jstring s)
{
	if (!s)
		return {};
	char const *c = env->GetStringUTFChars(s, nullptr);
	std::string r = c ? c : "";
	if (c)
		env->ReleaseStringUTFChars(s, c);
	return r;
}

// NewStringUTF exige "modified UTF-8"; caminhos/nomes com bytes inválidos
// abortariam a VM. Substitui sequências inválidas por '?'.
jstring to_j(JNIEnv *env, std::string const &s)
{
	std::string safe;
	safe.reserve(s.size());
	for (std::size_t i = 0; i < s.size(); )
	{
		unsigned char const c = s[i];
		int len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xe ? 3 : (c >> 3) == 0x1e ? 4 : 0;
		bool valid = len > 0 && len < 4 && i + len <= s.size() && c != 0;
		for (int k = 1; valid && k < len; ++k)
			valid = (static_cast<unsigned char>(s[i + k]) & 0xc0) == 0x80;
		if (valid)
		{
			safe.append(s, i, len);
			i += len;
		}
		else
		{
			safe.push_back('?');
			i += 1;
		}
	}
	return env->NewStringUTF(safe.c_str());
}

std::string json_escape(std::string const &s)
{
	std::string o;
	for (char c : s)
	{
		switch (c)
		{
		case '"': o += "\\\""; break;
		case '\\': o += "\\\\"; break;
		case '\n': o += "\\n"; break;
		case '\r': o += "\\r"; break;
		case '\t': o += "\\t"; break;
		default:
			if (static_cast<unsigned char>(c) >= 0x20)
				o += c;
		}
	}
	return o;
}

std::string result_json(Result const &r)
{
	return "{\"code\":" + std::to_string(int(r.code)) +
			",\"message\":\"" + json_escape(r.message) + "\"" +
			",\"logicalBytes\":" + std::to_string(r.logical_bytes) +
			",\"outputBytes\":" + std::to_string(r.output_bytes) +
			",\"hunkBytes\":" + std::to_string(r.hunk_bytes) +
			",\"compression\":\"" + json_escape(r.compression) + "\"" +
			",\"rawSha1\":\"" + r.raw_sha1 + "\"" +
			",\"sha1\":\"" + r.sha1 + "\"" +
			",\"tracks\":" + std::to_string(r.tracks) + "}";
}

// Encaminha progresso e logs para o listener Kotlin. Logs vindos de threads de
// trabalho do MAME são enfileirados e entregues na thread chamadora (que é a
// única com JNIEnv válido para o listener).
class ListenerBridge
{
public:
	ListenerBridge(JNIEnv *env, jobject listener) : m_env(env), m_listener(listener)
	{
		if (listener)
		{
			jclass cls = env->GetObjectClass(listener);
			m_onProgress = env->GetMethodID(cls, "onProgress", "(IDDJJJ)V");
			m_onLog = env->GetMethodID(cls, "onLog", "(Ljava/lang/String;)V");
			env->DeleteLocalRef(cls);
			if (env->ExceptionCheck())
			{
				env->ExceptionClear();
				m_onProgress = nullptr;
				m_onLog = nullptr;
			}
		}
	}

	Options options(int threads, int hunk = 0, std::string codecs = {})
	{
		Options opt;
		opt.threads = threads;
		opt.hunk_bytes = unsigned(hunk > 0 ? hunk : 0);
		opt.codecs = std::move(codecs);
		opt.cancel = &g_cancel;
		opt.progress_interval_ms = 250; // ~4 atualizações/s: suficiente para a interface
		opt.progress = [this](Progress const &p) { on_progress(p); };
		opt.log = [this](std::string const &m) { on_log(m); };
		return opt;
	}

	void flush_logs()
	{
		std::vector<std::string> pending;
		{
			std::lock_guard<std::mutex> lock(m_mutex);
			pending.swap(m_pending);
		}
		if (!m_listener || !m_onLog)
			return;
		for (auto const &m : pending)
		{
			jstring js = to_j(m_env, m);
			m_env->CallVoidMethod(m_listener, m_onLog, js);
			m_env->DeleteLocalRef(js);
			check_exception();
		}
	}

private:
	void on_progress(Progress const &p)
	{
		flush_logs();
		if (m_listener && m_onProgress)
		{
			m_env->CallVoidMethod(m_listener, m_onProgress, jint(p.stage), jdouble(p.fraction), jdouble(p.ratio),
					jlong(p.done), jlong(p.total), jlong(p.written));
			check_exception();
		}
	}

	void on_log(std::string const &m)
	{
		ALOGI("%s", m.c_str());
		std::lock_guard<std::mutex> lock(m_mutex);
		if (m_pending.size() < 2000)
			m_pending.push_back(m);
	}

	void check_exception()
	{
		if (m_env->ExceptionCheck())
		{
			// uma exceção no listener não pode derrubar a conversão nativa
			m_env->ExceptionDescribe();
			m_env->ExceptionClear();
		}
	}

	JNIEnv *m_env;
	jobject m_listener;
	jmethodID m_onProgress = nullptr;
	jmethodID m_onLog = nullptr;
	std::mutex m_mutex;
	std::vector<std::string> m_pending;
};

} // anonymous namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_conversorchd_nativebridge_NativeChd_coreVersion(JNIEnv *env, jclass)
{
	return to_j(env, core_version());
}

JNIEXPORT jstring JNICALL
Java_com_conversorchd_nativebridge_NativeChd_probeImage(JNIEnv *env, jclass, jint fd)
{
	return to_j(env, probe_image_json(fd));
}

JNIEXPORT jstring JNICALL
Java_com_conversorchd_nativebridge_NativeChd_probeToc(JNIEnv *env, jclass, jstring path)
{
	return to_j(env, probe_toc_json(to_std(env, path)));
}

JNIEXPORT jstring JNICALL
Java_com_conversorchd_nativebridge_NativeChd_chdInfo(JNIEnv *env, jclass, jint fd)
{
	return to_j(env, chd_info_json(fd));
}

JNIEXPORT void JNICALL
Java_com_conversorchd_nativebridge_NativeChd_cancel(JNIEnv *, jclass)
{
	ALOGI("cancelamento solicitado");
	g_cancel.store(true);
}

JNIEXPORT jstring JNICALL
Java_com_conversorchd_nativebridge_NativeChd_convertDvd(JNIEnv *env, jclass, jint inFd, jint outFd, jint threads,
		jint hunk, jstring codecs, jobject listener)
{
	g_cancel.store(false);
	ListenerBridge bridge(env, listener);
	std::string const cs = to_std(env, codecs);
	ALOGI("convertDvd in=%d out=%d threads=%d hunk=%d codecs=%s", inFd, outFd, threads, hunk, cs.c_str());
	Result const r = convert_dvd(inFd, outFd, bridge.options(threads, hunk, cs));
	bridge.flush_logs();
	ALOGI("convertDvd resultado=%d %s", int(r.code), r.message.c_str());
	return to_j(env, result_json(r));
}

JNIEXPORT jstring JNICALL
Java_com_conversorchd_nativebridge_NativeChd_convertCd(JNIEnv *env, jclass, jstring tocPath, jobjectArray trackPaths,
		jintArray trackFds, jint outFd, jint threads, jint hunk, jstring codecs, jobject listener)
{
	g_cancel.store(false);
	ListenerBridge bridge(env, listener);
	std::string const path = to_std(env, tocPath);

	std::vector<TrackFd> tracks;
	jsize const n = trackPaths ? env->GetArrayLength(trackPaths) : 0;
	jsize const nfd = trackFds ? env->GetArrayLength(trackFds) : 0;
	if (n != nfd)
		return to_j(env, "{\"code\":7,\"message\":\"track arrays mismatch\"}");
	if (n > 0)
	{
		std::vector<jint> fds(n);
		env->GetIntArrayRegion(trackFds, 0, n, fds.data());
		for (jsize i = 0; i < n; ++i)
		{
			auto js = static_cast<jstring>(env->GetObjectArrayElement(trackPaths, i));
			tracks.push_back({ to_std(env, js), fds[i] });
			env->DeleteLocalRef(js);
		}
	}
	std::string const cs = to_std(env, codecs);
	ALOGI("convertCd toc=%s trilhas=%d out=%d threads=%d hunk=%d codecs=%s", path.c_str(), int(n), outFd, threads, hunk, cs.c_str());
	Result const r = convert_cd(path, tracks, outFd, bridge.options(threads, hunk, cs));
	bridge.flush_logs();
	ALOGI("convertCd resultado=%d %s", int(r.code), r.message.c_str());
	return to_j(env, result_json(r));
}

JNIEXPORT jstring JNICALL
Java_com_conversorchd_nativebridge_NativeChd_verify(JNIEnv *env, jclass, jint fd, jint threads, jobject listener)
{
	g_cancel.store(false);
	ListenerBridge bridge(env, listener);
	Result const r = verify(fd, bridge.options(threads));
	bridge.flush_logs();
	ALOGI("verify resultado=%d %s", int(r.code), r.message.c_str());
	return to_j(env, result_json(r));
}

} // extern "C"
