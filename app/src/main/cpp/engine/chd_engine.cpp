// SPDX-License-Identifier: BSD-3-Clause
//
// Motor de conversão CHD.
//
// O arquivo chdman.cpp oficial do MAME é incluído abaixo SEM MODIFICAÇÕES (apenas
// o símbolo main é renomeado), para reaproveitar exatamente as mesmas classes de
// leitura de trilhas (chd_cd_compressor), os mesmos codecs padrão e a mesma
// lógica de preenchimento de trilhas usados por "chdman createcd/createdvd".

#if defined(__GNUC__)
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-function"
#pragma GCC diagnostic ignored "-Wunused-variable"
#pragma GCC diagnostic ignored "-Wunused-but-set-variable"
#pragma GCC diagnostic ignored "-Wsign-compare"
#pragma GCC diagnostic ignored "-Wmisleading-indentation"
#endif
#define main chdman_cli_main
#include "../mame/src/tools/chdman.cpp"
#undef main
#if defined(__GNUC__)
#pragma GCC diagnostic pop
#endif

#include "chd_engine.h"
#include "fd_file.h"

#include <chrono>
#include <condition_variable>
#include <functional>
#include <map>
#include <thread>
#include <mutex>
#include <sstream>
#include <streambuf>

#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#ifndef CHDCONV_MAME_COMMIT
#define CHDCONV_MAME_COMMIT "desconhecido"
#endif

// Símbolos de versão exigidos pelo chdman.cpp
extern const char bare_build_version[];
extern const char build_version[];
const char bare_build_version[] = "0.283";
const char build_version[] = "0.283 (CHDify, MAME " CHDCONV_MAME_COMMIT ")";

// Definido em src/osd/osdsync.cpp: número de threads da fila de trabalho (0 = auto)
extern int osd_num_processors;

namespace chdconv {

namespace {

// Apenas uma operação por vez: osd_num_processors e a captura de std::cerr são globais.
std::mutex g_engine_mutex;

// ---------------------------------------------------------------------------
//  Captura de std::cout/std::cerr (mensagens do chdman) para o log do app
// ---------------------------------------------------------------------------
class LineLogBuf : public std::streambuf
{
public:
	explicit LineLogBuf(LogFn const &fn, std::string prefix) : m_fn(fn), m_prefix(std::move(prefix)) { }
	~LineLogBuf() override { flush_line(); }

	std::string const &last_line() const { return m_last; }

protected:
	int_type overflow(int_type ch) override
	{
		if (ch == traits_type::eof())
			return traits_type::not_eof(ch);
		std::lock_guard<std::mutex> lock(m_mutex);
		if (ch == '\n' || ch == '\r')
			flush_line_locked();
		else
			m_line.push_back(char(ch));
		return ch;
	}

	std::streamsize xsputn(char const *s, std::streamsize n) override
	{
		for (std::streamsize i = 0; i < n; ++i)
			overflow(traits_type::to_int_type(s[i]));
		return n;
	}

private:
	void flush_line() { std::lock_guard<std::mutex> lock(m_mutex); flush_line_locked(); }
	void flush_line_locked()
	{
		// remove espaços finais usados pelo chdman para apagar a linha de progresso
		while (!m_line.empty() && (m_line.back() == ' ' || m_line.back() == '\t'))
			m_line.pop_back();
		if (!m_line.empty())
		{
			m_last = m_line;
			if (m_fn)
				m_fn(m_prefix + m_line);
		}
		m_line.clear();
	}

	LogFn const &m_fn;
	std::string m_prefix;
	std::string m_line;
	std::string m_last;
	std::mutex m_mutex;
};

class ScopedStreamCapture
{
public:
	explicit ScopedStreamCapture(LogFn const &fn)
		: m_out(fn, "[chdman] ")
		, m_err(fn, "[chdman:erro] ")
		, m_oldout(std::cout.rdbuf(&m_out))
		, m_olderr(std::cerr.rdbuf(&m_err))
	{
	}
	~ScopedStreamCapture()
	{
		std::cout.flush();
		std::cerr.flush();
		std::cout.rdbuf(m_oldout);
		std::cerr.rdbuf(m_olderr);
	}
	std::string const &last_error() const { return m_err.last_line(); }

private:
	LineLogBuf m_out;
	LineLogBuf m_err;
	std::streambuf *m_oldout;
	std::streambuf *m_olderr;
};

// ---------------------------------------------------------------------------
//  Utilidades
// ---------------------------------------------------------------------------
class Throttle
{
public:
	explicit Throttle(unsigned ms) : m_interval(ms) { }
	bool ready(bool force = false)
	{
		auto const now = std::chrono::steady_clock::now();
		if (force || m_first || now - m_last >= m_interval)
		{
			m_first = false;
			m_last = now;
			return true;
		}
		return false;
	}

private:
	std::chrono::milliseconds m_interval;
	std::chrono::steady_clock::time_point m_last{};
	bool m_first = true;
};

bool is_cancelled(Options const &opt)
{
	return opt.cancel && opt.cancel->load(std::memory_order_relaxed);
}

void log(Options const &opt, std::string const &msg)
{
	if (opt.log)
		opt.log(msg);
}

void emit(Options const &opt, Throttle &t, Stage stage, double fraction, double ratio, std::uint64_t total, bool force = false,
		std::uint64_t written = 0)
{
	if (!opt.progress || !t.ready(force))
		return;
	if (fraction < 0.0) fraction = 0.0;
	if (fraction > 1.0) fraction = 1.0;
	opt.progress(Progress{ stage, fraction, ratio, std::uint64_t(double(total) * fraction), total, written });
}

bool is_no_space(int e)
{
	return e == ENOSPC
#ifdef EDQUOT
		|| e == EDQUOT
#endif
		|| e == EFBIG;
}

std::string json_escape(std::string_view s)
{
	std::string o;
	o.reserve(s.size() + 8);
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
			if (static_cast<unsigned char>(c) < 0x20)
				o += util::string_format("\\u%04x", unsigned(static_cast<unsigned char>(c)));
			else
				o += c;
		}
	}
	return o;
}

std::string codecs_string(chd_codec_type const (&c)[4])
{
	chd_codec_type tmp[4] = { c[0], c[1], c[2], c[3] };
	return compression_string(tmp); // função do chdman.cpp
}

// ---------------------------------------------------------------------------
//  Compressores
// ---------------------------------------------------------------------------

// Leitor de ISO de DVD por descritor. Equivale ao chd_rawfile_compressor do
// chdman, mas propaga erros de leitura (o original devolve 0 bytes em silêncio,
// o que poderia gerar um CHD com setores zerados) e respeita o cancelamento.
class FdRawCompressor : public chd_file_compressor
{
public:
	FdRawCompressor(FdFile &file, std::uint64_t length, std::atomic<bool> *cancel)
		: m_file(file), m_length(length), m_cancel(cancel) { }

	std::uint32_t read_data(void *dest, std::uint64_t offset, std::uint32_t length) override
	{
		if (m_cancel && m_cancel->load(std::memory_order_relaxed))
			throw std::error_condition(std::errc::operation_canceled);
		if (offset >= m_length)
			return 0;
		if (offset + length > m_length)
			length = std::uint32_t(m_length - offset);
		std::size_t actual = 0;
		std::error_condition const err = m_file.read_some_at(offset, dest, length, actual);
		if (err)
			throw err;
		if (actual != length)
			throw std::error_condition(std::errc::io_error); // arquivo encolheu durante a leitura
		return length;
	}

private:
	FdFile &m_file;
	std::uint64_t m_length;
	std::atomic<bool> *m_cancel;
};

// chd_cd_compressor oficial do chdman + verificação de cancelamento.
class CancellableCdCompressor : public chd_cd_compressor
{
public:
	CancellableCdCompressor(cdrom_file::toc &toc, cdrom_file::track_input_info &info, std::atomic<bool> *cancel)
		: chd_cd_compressor(toc, info), m_cancel(cancel) { }

	uint32_t read_data(void *dest, uint64_t offset, uint32_t length) override
	{
		if (m_cancel && m_cancel->load(std::memory_order_relaxed))
			throw std::error_condition(std::errc::operation_canceled);
		return chd_cd_compressor::read_data(dest, offset, length);
	}

private:
	std::atomic<bool> *m_cancel;
};

// Leitor de trilhas de CD por descritor de arquivo.
//
// Mesma lógica do chd_cd_compressor::read_data() do chdman (trilhas divididas,
// pré-enchimento, troca de bytes de áudio), mas os arquivos de trilha são lidos
// a partir de descritores já abertos pelo app (SAF) em vez de reabertos pelo
// caminho. Isso evita reabrir arquivos de /storage via FUSE, o que pode ser
// negado no Android 11+ para apps sem permissão ampla de armazenamento.
class FdCdCompressor : public chd_file_compressor
{
public:
	using TrackFiles = std::map<std::string, FdFile *>;

	FdCdCompressor(cdrom_file::toc &toc, cdrom_file::track_input_info &info, TrackFiles files, std::atomic<bool> *cancel)
		: m_toc(toc), m_info(info), m_files(std::move(files)), m_cancel(cancel) { }

	uint32_t read_data(void *_dest, uint64_t offset, uint32_t length) override
	{
		if (m_cancel && m_cancel->load(std::memory_order_relaxed))
			throw std::error_condition(std::errc::operation_canceled);

		assert(offset % cdrom_file::FRAME_SIZE == 0);
		assert(length % cdrom_file::FRAME_SIZE == 0);

		uint8_t *dest = reinterpret_cast<uint8_t *>(_dest);
		memset(dest, 0, length);

		uint64_t startoffs = 0;
		uint32_t length_remaining = length;
		for (int tracknum = 0; tracknum < m_toc.numtrks; tracknum++)
		{
			const cdrom_file::track_info &trackinfo = m_toc.tracks[tracknum];
			uint64_t endoffs = startoffs + (uint64_t)(trackinfo.frames + trackinfo.extraframes) * cdrom_file::FRAME_SIZE;

			if (offset >= startoffs && offset < endoffs)
			{
				if (!m_file || m_lastfile != m_info.track[tracknum].fname)
					select(m_info.track[tracknum].fname);

				uint64_t bytesperframe = trackinfo.datasize + trackinfo.subsize;
				uint64_t src_track_start = m_info.track[tracknum].offset;
				uint64_t src_track_end = src_track_start + bytesperframe * (uint64_t)trackinfo.frames;
				uint64_t split_track_start = src_track_end - ((uint64_t)trackinfo.splitframes * bytesperframe);
				uint64_t pad_track_start = split_track_start - ((uint64_t)trackinfo.padframes * bytesperframe);

				if ((uint64_t)trackinfo.splitframes == 0L)
					split_track_start = UINT64_MAX;

				while (length_remaining != 0 && offset < endoffs)
				{
					uint64_t src_frame_start = src_track_start + ((offset - startoffs) / cdrom_file::FRAME_SIZE) * bytesperframe;

					if (src_frame_start >= split_track_start && src_frame_start < src_track_end && m_lastfile != m_info.track[tracknum + 1].fname)
						select(m_info.track[tracknum + 1].fname);

					if (src_frame_start < src_track_end)
					{
						if (src_frame_start >= pad_track_start && src_frame_start < split_track_start)
						{
							memset(dest, 0, bytesperframe);
						}
						else
						{
							uint64_t const pos = (src_frame_start >= split_track_start) ? src_frame_start - split_track_start : src_frame_start;
							std::size_t count = 0;
							std::error_condition err = m_file->read_some_at(pos, dest, bytesperframe, count);
							if (err)
								throw err;
							if (count != bytesperframe)
								throw std::error_condition(std::errc::io_error);
						}

						if (m_info.track[tracknum].swap)
							for (uint32_t swapindex = 0; swapindex < 2352; swapindex += 2)
								std::swap(dest[swapindex], dest[swapindex + 1]);
					}

					offset += cdrom_file::FRAME_SIZE;
					dest += cdrom_file::FRAME_SIZE;
					length_remaining -= cdrom_file::FRAME_SIZE;
					if (length_remaining == 0)
						break;
				}
			}
			startoffs = endoffs;
		}
		return length - length_remaining;
	}

private:
	void select(std::string const &fname)
	{
		auto it = m_files.find(fname);
		if (it == m_files.end() || !it->second)
		{
			osd_printf_error("Track file not provided: %s\n", fname);
			throw std::error_condition(std::errc::no_such_file_or_directory);
		}
		m_file = it->second;
		m_lastfile = fname;
	}

	cdrom_file::toc &m_toc;
	cdrom_file::track_input_info &m_info;
	TrackFiles m_files;
	std::atomic<bool> *m_cancel;
	FdFile *m_file = nullptr;
	std::string m_lastfile;
};

// ---------------------------------------------------------------------------
//  Laço de compressão comum (equivalente a compress_common do chdman)
// ---------------------------------------------------------------------------
Result run_compression(chd_file_compressor &chd, FdFile &out, Options const &opt, std::uint64_t logical,
		ScopedStreamCapture &cap)
{
	Result r;
	r.logical_bytes = logical;
	Throttle throttle(opt.progress_interval_ms);
	double complete = 0.0, ratio = 1.0;
	std::error_condition err;
	auto const t0 = std::chrono::steady_clock::now();

	try
	{
		chd.compress_begin();
		emit(opt, throttle, Stage::COMPRESS, 0.0, 1.0, logical, true);
		for (;;)
		{
			if (is_cancelled(opt))
			{
				r.code = ResultCode::CANCELLED;
				r.message = "cancelled";
				return r;
			}
			err = chd.compress_continue(complete, ratio);
			if (err != chd_file::error::COMPRESSING && err != chd_file::error::WALKING_PARENT)
				break;
			emit(opt, throttle, Stage::COMPRESS, complete, ratio, logical, false, out.bytes_written());
		}
	}
	catch (std::error_condition const &e)
	{
		err = e;
	}
	catch (fatal_error const &e)
	{
		err = std::errc::io_error;
		r.message = cap.last_error();
	}
	catch (std::bad_alloc const &)
	{
		err = std::errc::not_enough_memory;
	}
	catch (std::exception const &e)
	{
		err = std::errc::io_error;
		r.message = e.what();
	}

	if (err)
	{
		if (is_cancelled(opt))
		{
			r.code = ResultCode::CANCELLED;
			r.message = "cancelled";
		}
		else if (is_no_space(out.last_errno()))
		{
			r.code = ResultCode::NO_SPACE;
			r.message = "no space left on device";
		}
		else
		{
			r.code = (err == std::errc::io_error || err.category() == std::generic_category()) ? ResultCode::IO_ERROR : ResultCode::CHD_ERROR;
			if (r.message.empty())
				r.message = err.message();
			if (!cap.last_error().empty() && r.message.find(cap.last_error()) == std::string::npos)
				r.message += " | " + cap.last_error();
		}
		log(opt, "Falha na compressão: " + r.message);
		return r;
	}

	emit(opt, throttle, Stage::COMPRESS, 1.0, ratio, logical, true, out.bytes_written());

	if (std::error_condition const se = out.sync())
	{
		r.code = is_no_space(out.last_errno()) ? ResultCode::NO_SPACE : ResultCode::IO_ERROR;
		r.message = "fsync: " + se.message();
		return r;
	}

	std::uint64_t outlen = 0;
	out.length(outlen);
	r.code = ResultCode::OK;
	r.output_bytes = outlen;
	r.hunk_bytes = chd.hunk_bytes();
	r.raw_sha1 = chd.raw_sha1().as_string();
	r.sha1 = chd.sha1().as_string();
	double const secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
	log(opt, util::string_format("Compressão concluída: %u bytes lógicos -> %u bytes (%.1f%%) em %.1f s",
			logical, outlen, 100.0 * ratio, secs));
	return r;
}

std::unique_ptr<FdFile> open_output(int out_fd, Result &r)
{
	std::error_condition err;
	auto out = FdFile::from_fd(out_fd, true, err);
	if (!out)
	{
		r.code = ResultCode::IO_ERROR;
		r.message = "output: " + err.message();
		return nullptr;
	}
	// O SAF pode abrir em modo "rw" sem truncar: garante um arquivo vazio.
	if (::ftruncate(out->fd(), 0) != 0 && errno != EINVAL)
	{
		r.code = ResultCode::IO_ERROR;
		r.message = std::string("ftruncate: ") + std::strerror(errno);
		return nullptr;
	}
	return out;
}

// A fila de compressão do MAME (WORK_QUEUE_FLAG_MULTI) cria N-1 threads, onde N
// é osd_num_processors; a thread que chama compress_continue() só lê, grava e
// espera. Por isso "automático" usa núcleos + 1: todos os núcleos comprimem.
// É o mesmo que "chdman -np <núcleos+1>"; o MAME limita a 4x os núcleos físicos.
void configure_threads(Options const &opt)
{
	int const cores = std::max(1u, std::thread::hardware_concurrency());
	int const workers = opt.threads > 0 ? opt.threads : cores;
	osd_num_processors = workers + 1;
}

int effective_threads(Options const &opt)
{
	return opt.threads > 0 ? opt.threads : std::max(1u, std::thread::hardware_concurrency());
}

// Interpreta a lista de codecs e valida contra o tipo de mídia.
bool parse_codecs(std::string const &spec, bool cd, chd_codec_type (&out)[4], std::string &err)
{
	for (auto &c : out)
		c = CHD_CODEC_NONE;
	if (spec.empty())
		return true;
	static char const *const dvd_ok[] = { "lzma", "zlib", "huff", "flac", "zstd" };
	static char const *const cd_ok[] = { "cdlz", "cdzl", "cdfl", "cdzs" };
	int n = 0;
	std::size_t pos = 0;
	while (pos <= spec.size())
	{
		std::size_t const comma = spec.find(',', pos);
		std::string const name = spec.substr(pos, comma == std::string::npos ? std::string::npos : comma - pos);
		pos = comma == std::string::npos ? spec.size() + 1 : comma + 1;
		if (name.empty())
			continue;
		bool valid = false;
		if (cd)
			for (char const *v : cd_ok) valid = valid || name == v;
		else
			for (char const *v : dvd_ok) valid = valid || name == v;
		if (!valid || name.size() != 4)
		{
			err = "codec not allowed for this media: " + name;
			return false;
		}
		if (n == 4)
		{
			err = "at most 4 codecs";
			return false;
		}
		out[n++] = CHD_MAKE_TAG(name[0], name[1], name[2], name[3]);
	}
	return true;
}

// Valida o tamanho do hunk (mesmas regras do chdman: múltiplo da unidade, até 1 MiB).
bool check_hunk(unsigned hunk, unsigned unit, std::string &err)
{
	if (hunk == 0)
		return true;
	if (hunk % unit != 0 || hunk < unit || hunk > HUNK_SIZE_MAX)
	{
		err = util::string_format("invalid hunk size %u (must be a multiple of %u, up to %u)", hunk, unit, HUNK_SIZE_MAX);
		return false;
	}
	return true;
}

// ---------------------------------------------------------------------------
//  Verificação paralela
//
//  O "chdman verify" lê o CHD sequencialmente numa única thread; a
//  descompressão (LZMA/FLAC) é o gargalo. Aqui várias threads descomprimem
//  blocos diferentes, cada uma com sua própria instância de chd_file (o estado
//  dos decompressores não é compartilhável), e a thread chamadora acumula o
//  SHA-1 dos blocos estritamente em ordem. O resultado é o mesmo SHA-1 que o
//  chdman calcularia. A memória fica limitada: 2 blocos de 2 MiB por thread, e
//  o número de instâncias é reduzido se o mapa de hunks do CHD for grande.
// ---------------------------------------------------------------------------
struct VerifyOutcome
{
	std::error_condition err;
	std::uint64_t err_offset = 0;
	bool cancelled = false;
	bool alloc_failed = false;
	bool sink_failed = false;   // erro de gravação no destino (extração)
	util::sha1_t sha1;
};

// Recebe os blocos descomprimidos em ordem (extração grava no destino).
using ChunkSink = std::function<std::error_condition(std::uint64_t offset, std::uint8_t const *data, std::uint32_t length)>;

int verify_workers(chd_file &chd, Options const &opt)
{
	int workers = std::clamp(effective_threads(opt), 1, 6);
	// mapa descomprimido do CHD v5: 12 bytes por hunk, por instância
	std::uint64_t const map_bytes = std::uint64_t(chd.hunk_count()) * 12;
	while (workers > 1 && std::uint64_t(workers - 1) * map_bytes > 48ULL * 1024 * 1024)
		--workers;
	if (chd.logical_bytes() < 8ULL * 1024 * 1024)
		workers = 1;
	return workers;
}

VerifyOutcome parallel_verify(int fd, chd_file &primary, int workers, Options const &opt, Throttle &throttle,
		Stage stage = Stage::VERIFY, ChunkSink const &sink = {})
{
	VerifyOutcome out;
	std::uint64_t const total = primary.logical_bytes();
	std::uint32_t const hunk = primary.hunk_bytes();
	std::uint32_t const chunk = std::max<std::uint32_t>(1, (2 * 1024 * 1024) / hunk) * hunk;
	std::uint64_t const nchunks = (total + chunk - 1) / chunk;

	// instâncias: a principal + (workers - 1) extras, cada uma com seu descritor
	std::vector<chd_file *> inst{ &primary };
	std::vector<std::unique_ptr<chd_file>> extra;
	for (int i = 1; i < workers; ++i)
	{
		std::error_condition e;
		auto f = FdFile::from_fd(fd, false, e);
		if (!f)
			break;
		auto c = std::make_unique<chd_file>();
		if (c->open(std::move(f)))
			break;
		inst.push_back(c.get());
		extra.push_back(std::move(c));
	}
	std::size_t const nthreads = inst.size();
	std::size_t const slots = nthreads * 2;

	std::vector<std::vector<std::uint8_t>> buf;
	try
	{
		buf.assign(slots, std::vector<std::uint8_t>(chunk));
	}
	catch (std::bad_alloc const &)
	{
		out.err = std::errc::not_enough_memory;
		out.alloc_failed = true;
		return out;
	}
	std::vector<std::int64_t> ready(slots, -1);
	std::mutex m;
	std::condition_variable cv_ready, cv_free;
	std::uint64_t next = 0, consumed = 0;
	bool stop = false;

	auto worker = [&](chd_file *chd) {
		for (;;)
		{
			std::uint64_t c;
			{
				std::unique_lock<std::mutex> lock(m);
				cv_free.wait(lock, [&] { return stop || next >= nchunks || next < consumed + slots; });
				if (stop || next >= nchunks)
					return;
				c = next++;
			}
			std::uint64_t const off = c * chunk;
			std::uint32_t const n = std::uint32_t(std::min<std::uint64_t>(chunk, total - off));
			std::error_condition const e = chd->read_bytes(off, buf[c % slots].data(), n);
			std::lock_guard<std::mutex> lock(m);
			if (e)
			{
				if (!out.err)
				{
					out.err = e;
					out.err_offset = off;
				}
				stop = true;
				cv_ready.notify_all();
				cv_free.notify_all();
				return;
			}
			ready[c % slots] = std::int64_t(c);
			cv_ready.notify_all();
		}
	};

	std::vector<std::thread> threads;
	threads.reserve(nthreads);
	for (chd_file *c : inst)
		threads.emplace_back(worker, c);

	util::sha1_creator sha;
	for (std::uint64_t c = 0; c < nchunks; ++c)
	{
		{
			std::unique_lock<std::mutex> lock(m);
			if (is_cancelled(opt))
			{
				out.cancelled = true;
				stop = true;
				cv_free.notify_all();
			}
			while (!stop && ready[c % slots] != std::int64_t(c))
			{
				cv_ready.wait_for(lock, std::chrono::milliseconds(100));
				if (is_cancelled(opt))
				{
					out.cancelled = true;
					stop = true;
					cv_free.notify_all();
				}
			}
			if (stop)
				break;
		}
		std::uint64_t const off = c * chunk;
		std::uint32_t const n = std::uint32_t(std::min<std::uint64_t>(chunk, total - off));
		sha.append(buf[c % slots].data(), n);
		if (sink)
		{
			std::error_condition const se = sink(off, buf[c % slots].data(), n);
			if (se)
			{
				std::lock_guard<std::mutex> lock(m);
				out.err = se;
				out.err_offset = off;
				out.sink_failed = true;
				stop = true;
				cv_free.notify_all();
				break;
			}
		}
		emit(opt, throttle, stage, double(off + n) / double(total), 1.0, total, false, sink ? off + n : 0);
		{
			std::lock_guard<std::mutex> lock(m);
			ready[c % slots] = -1;
			++consumed;
		}
		cv_free.notify_all();
	}
	{
		std::lock_guard<std::mutex> lock(m);
		stop = true;
	}
	cv_free.notify_all();
	for (auto &t : threads)
		t.join();
	if (!out.err && !out.cancelled)
		out.sha1 = sha.finish();
	return out;
}

} // anonymous namespace

// ===========================================================================
//  API pública
// ===========================================================================

Result convert_dvd(int in_fd, int out_fd, Options const &opt)
{
	std::lock_guard<std::mutex> lock(g_engine_mutex);
	ScopedStreamCapture cap(opt.log);
	Result r;
	configure_threads(opt);

	std::error_condition err;
	auto in = FdFile::from_fd(in_fd, false, err);
	if (!in)
	{
		r.code = ResultCode::IO_ERROR;
		r.message = "input: " + err.message();
		return r;
	}
	std::uint64_t input_size = 0;
	if ((err = in->length(input_size)))
	{
		r.code = ResultCode::IO_ERROR;
		r.message = "input length: " + err.message();
		return r;
	}
	if (input_size == 0 || (input_size % 2048) != 0)
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = util::string_format("Data size %u is not divisible by sector size 2048", input_size);
		return r;
	}

	auto outptr = open_output(out_fd, r);
	if (!outptr)
		return r;
	FdFile &out = *outptr;

	// padrão de "chdman createdvd": hunk de 2 setores, lzma/zlib/huff/flac
	std::string perr;
	chd_codec_type compression[4];
	if (!check_hunk(opt.hunk_bytes, 2048, perr) || !parse_codecs(opt.codecs, false, compression, perr))
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = perr;
		return r;
	}
	uint32_t const hunk_size = opt.hunk_bytes ? opt.hunk_bytes : 2 * 2048;
	if (opt.codecs.empty())
		for (int i = 0; i < 4; ++i)
			compression[i] = s_default_hd_compression[i];
	r.compression = codecs_string(compression);

	log(opt, util::string_format("Modo DVD (createdvd): %u bytes, hunk %u, codecs %s, threads %d",
			input_size, hunk_size, r.compression, effective_threads(opt)));

	try
	{
		auto chd = std::make_unique<FdRawCompressor>(*in, input_size, opt.cancel);
		err = chd->create(std::move(outptr), input_size, hunk_size, 2048, compression);
		if (err)
		{
			r.code = is_no_space(out.last_errno()) ? ResultCode::NO_SPACE : ResultCode::CHD_ERROR;
			r.message = "Error creating CHD file: " + err.message();
			return r;
		}
		err = chd->write_metadata(DVD_METADATA_TAG, 0, "");
		if (err)
		{
			r.code = is_no_space(out.last_errno()) ? ResultCode::NO_SPACE : ResultCode::CHD_ERROR;
			r.message = "Error adding DVD metadata: " + err.message();
			return r;
		}
		std::string const comp = r.compression;
		r = run_compression(*chd, out, opt, input_size, cap);
		r.compression = comp;
	}
	catch (std::bad_alloc const &)
	{
		r.code = ResultCode::INTERNAL_ERROR;
		r.message = "out of memory";
	}
	catch (std::exception const &e)
	{
		r.code = ResultCode::INTERNAL_ERROR;
		r.message = e.what();
	}
	return r;
}

Result convert_cd(std::string const &toc_path, std::vector<TrackFd> const &track_fds, int out_fd, Options const &opt)
{
	std::lock_guard<std::mutex> lock(g_engine_mutex);
	ScopedStreamCapture cap(opt.log);
	Result r;
	configure_threads(opt);

	// --- trecho equivalente a do_create_cd() ---
	auto info = std::make_unique<cdrom_file::track_input_info>();
	auto toc = std::make_unique<cdrom_file::toc>();
	std::memset(toc.get(), 0, sizeof(cdrom_file::toc));
	std::error_condition err = cdrom_file::parse_toc(toc_path, *toc, *info);
	if (err)
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = "Error parsing input file: " + err.message();
		if (!cap.last_error().empty())
			r.message += " | " + cap.last_error();
		return r;
	}
	if (toc->numtrks <= 0)
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = "no tracks found";
		return r;
	}
	if (toc->flags & cdrom_file::CD_FLAG_GDROM)
	{
		err = cdrom_file::adjust_high_density_area(*toc, *info);
		if (err)
		{
			r.code = ResultCode::INVALID_INPUT;
			r.message = "Error adjusting high density area: " + err.message();
			return r;
		}
	}

	std::string perr;
	chd_codec_type compression[4];
	if (!check_hunk(opt.hunk_bytes, cdrom_file::FRAME_SIZE, perr) || !parse_codecs(opt.codecs, true, compression, perr))
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = perr;
		return r;
	}
	uint32_t const hunk_size = opt.hunk_bytes ? opt.hunk_bytes : cdrom_file::FRAMES_PER_HUNK * cdrom_file::FRAME_SIZE;
	if (opt.codecs.empty())
		for (int i = 0; i < 4; ++i)
			compression[i] = s_default_cd_compression[i];
	r.compression = codecs_string(compression);

	// preenche cada trilha até múltiplo de 4 quadros (cdrom.cpp trata isso na leitura)
	uint32_t origtotalsectors = 0;
	uint32_t totalsectors = 0;
	for (int tracknum = 0; tracknum < toc->numtrks; tracknum++)
	{
		cdrom_file::track_info &trackinfo = toc->tracks[tracknum];
		int const padded = (trackinfo.frames + cdrom_file::TRACK_PADDING - 1) / cdrom_file::TRACK_PADDING;
		trackinfo.extraframes = padded * cdrom_file::TRACK_PADDING - trackinfo.frames;
		origtotalsectors += trackinfo.frames;
		totalsectors += trackinfo.frames + trackinfo.extraframes;
		log(opt, util::string_format("Trilha %d: %s/%s, %u quadros, dados %u, subcanal %u, arquivo %s",
				tracknum + 1, cdrom_file::get_type_string(trackinfo.trktype), cdrom_file::get_subtype_string(trackinfo.subtype),
				trackinfo.frames, trackinfo.datasize, trackinfo.subsize, info->track[tracknum].fname));
	}
	std::uint64_t const logical = std::uint64_t(totalsectors) * cdrom_file::FRAME_SIZE;
	log(opt, util::string_format("Modo CD (createcd): %d trilha(s), duração %s, hunk %u, codecs %s, threads %d",
			toc->numtrks, msf_string_from_frames(origtotalsectors), hunk_size, r.compression, effective_threads(opt)));

	auto outptr = open_output(out_fd, r);
	if (!outptr)
		return r;
	FdFile &out = *outptr;

	// Associa cada arquivo de trilha (caminho gerado pelo parse_toc) a um
	// descritor, comparando dispositivo+inode com os caminhos informados.
	std::vector<std::unique_ptr<FdFile>> owned;
	FdCdCompressor::TrackFiles files;
	if (!track_fds.empty())
	{
		for (int t = 0; t < toc->numtrks; ++t)
		{
			std::string const &fname = info->track[t].fname;
			if (files.count(fname))
				continue;
			struct stat ts;
			bool const have = ::stat(fname.c_str(), &ts) == 0;
			for (auto const &tf : track_fds)
			{
				struct stat ps;
				bool const match = tf.path == fname
						|| (have && ::stat(tf.path.c_str(), &ps) == 0 && ps.st_dev == ts.st_dev && ps.st_ino == ts.st_ino);
				if (!match)
					continue;
				std::error_condition ferr;
				auto f = FdFile::from_fd(tf.fd, false, ferr);
				if (!f)
				{
					r.code = ResultCode::IO_ERROR;
					r.message = "track " + fname + ": " + ferr.message();
					return r;
				}
				// confere se o descritor tem o tamanho que o parse_toc viu
				std::uint64_t flen = 0;
				f->length(flen);
				if (have && std::uint64_t(ts.st_size) != flen)
				{
					r.code = ResultCode::INVALID_INPUT;
					r.message = util::string_format("track %s size mismatch (%u vs %u)", fname, flen, std::uint64_t(ts.st_size));
					return r;
				}
				files[fname] = f.get();
				owned.push_back(std::move(f));
				break;
			}
			if (!files.count(fname))
			{
				r.code = ResultCode::INVALID_INPUT;
				r.message = "Missing track file: " + fname;
				return r;
			}
		}
	}

	try
	{
		std::unique_ptr<chd_file_compressor> chd;
		if (track_fds.empty())
			chd = std::make_unique<CancellableCdCompressor>(*toc, *info, opt.cancel);
		else
			chd = std::make_unique<FdCdCompressor>(*toc, *info, std::move(files), opt.cancel);
		err = chd->create(std::move(outptr), logical, hunk_size, cdrom_file::FRAME_SIZE, compression);
		if (err)
		{
			r.code = is_no_space(out.last_errno()) ? ResultCode::NO_SPACE : ResultCode::CHD_ERROR;
			r.message = "Error creating CHD file: " + err.message();
			return r;
		}
		err = cdrom_file::write_metadata(chd.get(), *toc);
		if (err)
		{
			r.code = is_no_space(out.last_errno()) ? ResultCode::NO_SPACE : ResultCode::CHD_ERROR;
			r.message = "Error adding CD metadata: " + err.message();
			return r;
		}
		std::string const comp = r.compression;
		int const tracks = toc->numtrks;
		r = run_compression(*chd, out, opt, logical, cap);
		r.compression = comp;
		r.tracks = tracks;
	}
	catch (std::bad_alloc const &)
	{
		r.code = ResultCode::INTERNAL_ERROR;
		r.message = "out of memory";
	}
	catch (std::exception const &e)
	{
		r.code = ResultCode::INTERNAL_ERROR;
		r.message = e.what();
	}
	return r;
}

Result verify(int chd_fd, Options const &opt)
{
	std::lock_guard<std::mutex> lock(g_engine_mutex);
	ScopedStreamCapture cap(opt.log);
	Result r;

	std::error_condition err;
	auto file = FdFile::from_fd(chd_fd, false, err);
	if (!file)
	{
		r.code = ResultCode::IO_ERROR;
		r.message = "open: " + err.message();
		return r;
	}

	// --- trecho equivalente a do_verify() ---
	chd_file input;
	err = input.open(std::move(file));
	if (err)
	{
		r.code = ResultCode::VERIFY_FAILED;
		r.message = "Error opening CHD file: " + err.message();
		return r;
	}
	if (!input.compressed())
	{
		r.code = ResultCode::VERIFY_FAILED;
		r.message = "CHD is uncompressed";
		return r;
	}
	util::sha1_t const raw_sha1 = (input.version() <= 3) ? input.sha1() : input.raw_sha1();
	if (raw_sha1 == util::sha1_t::null)
	{
		r.code = ResultCode::VERIFY_FAILED;
		r.message = "CHD has no checksum";
		return r;
	}

	std::uint64_t const total = input.logical_bytes();
	r.logical_bytes = total;
	r.hunk_bytes = input.hunk_bytes();
	Throttle throttle(opt.progress_interval_ms);
	int const workers = verify_workers(input, opt);
	log(opt, util::string_format("Verificação: %d thread(s) de descompressão, hunk %u", workers, input.hunk_bytes()));
	VerifyOutcome const vo = parallel_verify(chd_fd, input, workers, opt, throttle);
	if (vo.cancelled)
	{
		r.code = ResultCode::CANCELLED;
		r.message = "cancelled";
		return r;
	}
	if (vo.err)
	{
		r.code = vo.alloc_failed ? ResultCode::INTERNAL_ERROR : ResultCode::VERIFY_FAILED;
		r.message = vo.alloc_failed ? std::string("out of memory")
				: util::string_format("Error reading CHD at offset %u: %s", vo.err_offset, vo.err.message());
		return r;
	}
	emit(opt, throttle, Stage::VERIFY, 1.0, 1.0, total, true);
	util::sha1_t const computed = vo.sha1;
	r.raw_sha1 = computed.as_string();
	r.sha1 = input.sha1().as_string();

	if (raw_sha1 != computed)
	{
		r.code = ResultCode::VERIFY_FAILED;
		r.message = "Raw SHA1 mismatch: header " + raw_sha1.as_string() + " actual " + computed.as_string();
		log(opt, r.message);
		return r;
	}
	log(opt, "Raw SHA1 verification successful! " + computed.as_string());
	if (input.version() >= 4)
	{
		util::sha1_t const overall = input.compute_overall_sha1(computed);
		if (input.sha1() != overall)
		{
			r.code = ResultCode::VERIFY_FAILED;
			r.message = "Overall SHA1 mismatch: header " + input.sha1().as_string() + " actual " + overall.as_string();
			log(opt, r.message);
			return r;
		}
		log(opt, "Overall SHA1 verification successful! " + overall.as_string());
	}
	r.code = ResultCode::OK;
	return r;
}

// ===========================================================================
//  Extração e recompactação
// ===========================================================================

namespace {

std::error_condition open_chd(int fd, chd_file &chd)
{
	std::error_condition err;
	auto file = FdFile::from_fd(fd, false, err);
	if (!file)
		return err;
	return chd.open(std::move(file));
}

bool has_cd_metadata(chd_file &chd)
{
	std::string meta;
	for (chd_metadata_tag tag : { CDROM_TRACK_METADATA2_TAG, CDROM_TRACK_METADATA_TAG, CDROM_OLD_METADATA_TAG,
			GDROM_TRACK_METADATA_TAG, GDROM_OLD_METADATA_TAG })
		if (!chd.read_metadata(tag, 0, meta))
			return true;
	return false;
}

// Plano de extração de CD, com as mesmas regras de nomes e de folha do
// do_extract_cd() do chdman:
//  - GD-ROM -> .gdi com uma trilha por arquivo ("Nome01.bin", "Nome02.raw"...);
//  - CD de uma trilha -> "Nome.bin" + .cue;
//  - CD com várias trilhas -> "Nome (Track N).bin" + .cue (padrão Redump).
// Subcódigo, se existir, não cabe em .cue/.gdi e é omitido (como no chdman).
struct CdPlan
{
	bool gdi = false;
	bool split = false;
	int tracks = 0;
	bool subcode_dropped = false;
	std::vector<std::string> track_file;   // nome por trilha
	std::vector<std::string> files;        // nomes únicos, em ordem
	std::vector<std::uint64_t> sizes;      // tamanho final de cada arquivo
	std::string sheet;
	std::uint64_t total_bytes = 0;
};

CdPlan make_cd_plan(chd_file &chd, cdrom_file &cdrom, std::string const &base_in)
{
	CdPlan p;
	cdrom_file::toc const &toc = cdrom.get_toc();
	std::string base = base_in;
	for (char &c : base)
		if (c == '"')
			c = '\'';
	p.gdi = cdrom.is_gdrom();
	p.tracks = toc.numtrks;
	p.split = p.gdi || toc.numtrks > 1;
	int const mode = p.gdi ? MODE_GDI : MODE_CUEBIN;

	for (int t = 0; t < toc.numtrks; ++t)
	{
		std::string name;
		if (p.gdi)
			name = util::string_format("%s%02d%s", base, t + 1, toc.tracks[t].trktype == cdrom_file::CD_TRACK_AUDIO ? ".raw" : ".bin");
		else if (p.split)
			name = util::string_format(toc.numtrks >= 10 ? "%s (Track %02d).bin" : "%s (Track %d).bin", base, t + 1);
		else
			name = base + ".bin";
		p.track_file.push_back(name);
		if (p.files.empty() || p.files.back() != name)
		{
			p.files.push_back(name);
			p.sizes.push_back(0);
		}
	}

	std::ostringstream str;
	str.imbue(std::locale::classic());
	if (p.gdi)
		util::stream_format(str, "%d\n", toc.numtrks);
	std::uint64_t outputoffs = 0;
	std::uint32_t discoffs = 0;
	int sessionnum = -1;
	std::string current;
	std::size_t fileidx = 0;
	for (int t = 0; t < toc.numtrks; ++t)
	{
		cdrom_file::track_info const &info = toc.tracks[t];
		if (p.track_file[t] != current)
		{
			if (!current.empty())
				++fileidx;
			outputoffs = 0;
			if (!p.gdi)
				discoffs = 0;
			current = p.track_file[t];
		}
		if (mode == MODE_CUEBIN && toc.numsessions > 1 && sessionnum != int(info.session))
		{
			util::stream_format(str, "REM SESSION %02d\n", info.session + 1);
			sessionnum = info.session;
		}
		output_track_metadata(mode, str, t, info, current, discoffs, outputoffs);
		if (info.subtype != cdrom_file::CD_SUB_NONE)
			p.subcode_dropped = true;
		std::uint32_t const actualframes = info.frames - info.padframes + info.splitframes;
		// os quadros de "split" vêm da trilha anterior, com o tamanho dela
		std::uint64_t bytes = 0;
		for (std::uint32_t f = 0; f < actualframes; ++f)
		{
			int const trk = (t > 0 && f < info.splitframes) ? t - 1 : t;
			bytes += toc.tracks[trk].datasize;
		}
		outputoffs += bytes;
		p.sizes[fileidx] += bytes;
		p.total_bytes += bytes;
		discoffs += actualframes + info.padframes;
	}
	(void)chd;
	p.sheet = str.str();
	return p;
}

std::string plan_json(CdPlan const &p)
{
	std::ostringstream o;
	o << "{\"ok\":true,\"gdrom\":" << (p.gdi ? "true" : "false") << ",\"tracks\":" << p.tracks
	  << ",\"subcodeDropped\":" << (p.subcode_dropped ? "true" : "false")
	  << ",\"sheetExt\":\"" << (p.gdi ? "gdi" : "cue") << "\",\"totalBytes\":" << p.total_bytes << ",\"files\":[";
	for (std::size_t i = 0; i < p.files.size(); ++i)
		o << (i ? "," : "") << '"' << json_escape(p.files[i]) << '"';
	o << "],\"sizes\":[";
	for (std::size_t i = 0; i < p.sizes.size(); ++i)
		o << (i ? "," : "") << p.sizes[i];
	o << "],\"sheet\":\"" << json_escape(p.sheet) << "\"}";
	return o.str();
}

// Leitor do "chdman copy" + cancelamento.
class CancellableChdCompressor : public chd_chdfile_compressor
{
public:
	CancellableChdCompressor(chd_file &file, std::atomic<bool> *cancel) : chd_chdfile_compressor(file), m_cancel(cancel) { }

	uint32_t read_data(void *dest, uint64_t offset, uint32_t length) override
	{
		if (m_cancel && m_cancel->load(std::memory_order_relaxed))
			throw std::error_condition(std::errc::operation_canceled);
		return chd_chdfile_compressor::read_data(dest, offset, length);
	}

private:
	std::atomic<bool> *m_cancel;
};

} // anonymous namespace

Result extract_dvd(int chd_fd, int out_fd, Options const &opt)
{
	std::lock_guard<std::mutex> lock(g_engine_mutex);
	ScopedStreamCapture cap(opt.log);
	Result r;

	chd_file input;
	std::error_condition err = open_chd(chd_fd, input);
	if (err)
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = "Error opening CHD file: " + err.message();
		return r;
	}
	std::string meta;
	if (input.read_metadata(DVD_METADATA_TAG, 0, meta))
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = has_cd_metadata(input) ? "CHD contains a CD image (use CD extraction)" : "CHD is not a DVD image";
		return r;
	}
	util::sha1_t const expected = input.raw_sha1();
	std::uint64_t const total = input.logical_bytes();
	r.logical_bytes = total;
	r.hunk_bytes = input.hunk_bytes();

	auto outptr = open_output(out_fd, r);
	if (!outptr)
		return r;
	FdFile &out = *outptr;

	Throttle throttle(opt.progress_interval_ms);
	int const workers = verify_workers(input, opt);
	log(opt, util::string_format("Extração DVD: %u bytes, %d thread(s) de descompressão", total, workers));
	emit(opt, throttle, Stage::EXTRACT, 0.0, 1.0, total, true);
	VerifyOutcome const vo = parallel_verify(chd_fd, input, workers, opt, throttle, Stage::EXTRACT,
			[&out](std::uint64_t off, std::uint8_t const *data, std::uint32_t n) -> std::error_condition {
				std::size_t actual = 0;
				return out.write_some_at(off, data, n, actual);
			});
	if (vo.cancelled)
	{
		r.code = ResultCode::CANCELLED;
		r.message = "cancelled";
		return r;
	}
	if (vo.err)
	{
		if (vo.sink_failed)
			r.code = is_no_space(out.last_errno()) ? ResultCode::NO_SPACE : ResultCode::IO_ERROR;
		else
			r.code = vo.alloc_failed ? ResultCode::INTERNAL_ERROR : ResultCode::VERIFY_FAILED;
		r.message = util::string_format("%s at offset %u: %s", vo.sink_failed ? "write error" : "read error",
				vo.err_offset, vo.err.message());
		return r;
	}
	if (vo.sha1 != expected)
	{
		r.code = ResultCode::VERIFY_FAILED;
		r.message = "SHA1 mismatch: CHD " + expected.as_string() + " extracted " + vo.sha1.as_string();
		return r;
	}
	if (std::error_condition const se = out.sync())
	{
		r.code = is_no_space(out.last_errno()) ? ResultCode::NO_SPACE : ResultCode::IO_ERROR;
		r.message = "fsync: " + se.message();
		return r;
	}
	emit(opt, throttle, Stage::EXTRACT, 1.0, 1.0, total, true, total);
	r.code = ResultCode::OK;
	r.output_bytes = total;
	r.raw_sha1 = vo.sha1.as_string();
	r.sha1 = input.sha1().as_string();
	log(opt, "Extração concluída; SHA-1 dos dados confere com o CHD: " + r.raw_sha1);
	return r;
}

std::string plan_cd_extract_json(int chd_fd, std::string const &base_name)
{
	chd_file chd;
	std::error_condition const err = open_chd(chd_fd, chd);
	if (err)
		return "{\"ok\":false,\"error\":\"" + json_escape(err.message()) + "\"}";
	if (!has_cd_metadata(chd))
		return "{\"ok\":false,\"error\":\"not a CD/GD-ROM CHD\"}";
	try
	{
		cdrom_file cdrom(&chd);
		return plan_json(make_cd_plan(chd, cdrom, base_name));
	}
	catch (std::exception const &e)
	{
		return "{\"ok\":false,\"error\":\"" + json_escape(e.what()) + "\"}";
	}
}

Result extract_cd(int chd_fd, std::string const &base_name, std::vector<int> const &out_fds, Options const &opt)
{
	std::lock_guard<std::mutex> lock(g_engine_mutex);
	ScopedStreamCapture cap(opt.log);
	Result r;

	chd_file input;
	std::error_condition err = open_chd(chd_fd, input);
	if (err || !has_cd_metadata(input))
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = err ? "Error opening CHD file: " + err.message() : std::string("not a CD/GD-ROM CHD");
		return r;
	}
	try
	{
		cdrom_file cdrom(&input);
		cdrom_file::toc const &toc = cdrom.get_toc();
		CdPlan const plan = make_cd_plan(input, cdrom, base_name);
		if (out_fds.size() != plan.files.size())
		{
			r.code = ResultCode::INTERNAL_ERROR;
			r.message = "output count mismatch";
			return r;
		}
		std::vector<std::unique_ptr<FdFile>> outs;
		for (int fd : out_fds)
		{
			auto o = open_output(fd, r);
			if (!o)
				return r;
			outs.push_back(std::move(o));
		}
		r.tracks = plan.tracks;
		r.logical_bytes = plan.total_bytes;
		log(opt, util::string_format("Extração %s: %d trilha(s), %d arquivo(s), %u bytes", plan.gdi ? "GD-ROM (.gdi)" : "CD (.cue)",
				plan.tracks, int(plan.files.size()), plan.total_bytes));
		if (plan.subcode_dropped)
			log(opt, "Aviso: o CHD tem subcódigo; .cue/.gdi não comportam subcódigo e ele foi omitido (como no chdman)");

		int const mode = plan.gdi ? MODE_GDI : MODE_CUEBIN;
		Throttle throttle(opt.progress_interval_ms);
		std::vector<std::uint8_t> buffer;
		std::uint64_t done = 0;
		std::size_t fileidx = 0;
		std::string current;
		std::uint64_t outputoffs = 0;
		emit(opt, throttle, Stage::EXTRACT, 0.0, 1.0, plan.total_bytes, true);
		for (int t = 0; t < toc.numtrks; ++t)
		{
			cdrom_file::track_info const &info = toc.tracks[t];
			if (plan.track_file[t] != current)
			{
				if (!current.empty())
					++fileidx;
				current = plan.track_file[t];
				outputoffs = 0;
			}
			FdFile &out = *outs[fileidx];
			buffer.resize((2 * 1024 * 1024 / cdrom_file::MAX_SECTOR_DATA) * cdrom_file::MAX_SECTOR_DATA);
			std::uint32_t bufferoffs = 0;
			std::uint32_t const actualframes = info.frames - info.padframes + info.splitframes;
			for (std::uint32_t frame = 0; frame < actualframes; ++frame)
			{
				if ((frame & 255) == 0)
				{
					if (is_cancelled(opt))
					{
						r.code = ResultCode::CANCELLED;
						r.message = "cancelled";
						return r;
					}
					emit(opt, throttle, Stage::EXTRACT, double(done + bufferoffs) / double(std::max<std::uint64_t>(1, plan.total_bytes)),
							1.0, plan.total_bytes, false, done + bufferoffs);
				}
				int trk;
				std::uint32_t frameofs;
				if (t > 0 && frame < info.splitframes)
				{
					trk = t - 1;
					frameofs = toc.tracks[trk].frames - info.splitframes + frame;
				}
				else
				{
					trk = t;
					frameofs = frame - info.splitframes;
				}
				if (!cdrom.read_data(cdrom.get_track_start_phys(trk) + frameofs, &buffer[bufferoffs], toc.tracks[trk].trktype, true))
				{
					r.code = ResultCode::VERIFY_FAILED;
					r.message = util::string_format("Error reading frame %u from track %d", frame, trk + 1);
					return r;
				}
				// .cue e .gdi (CHD v5) guardam áudio em little-endian
				if (((mode == MODE_GDI && input.version() > 4) || mode == MODE_CUEBIN) && toc.tracks[trk].trktype == cdrom_file::CD_TRACK_AUDIO)
					for (std::uint32_t i = 0; i < toc.tracks[trk].datasize; i += 2)
						std::swap(buffer[bufferoffs + i], buffer[bufferoffs + i + 1]);
				bufferoffs += toc.tracks[trk].datasize;
				if (bufferoffs + cdrom_file::MAX_SECTOR_DATA > buffer.size() || frame == actualframes - 1)
				{
					std::size_t actual = 0;
					std::error_condition const we = out.write_some_at(outputoffs, buffer.data(), bufferoffs, actual);
					if (we)
					{
						r.code = is_no_space(out.last_errno()) ? ResultCode::NO_SPACE : ResultCode::IO_ERROR;
						r.message = "write error: " + we.message();
						return r;
					}
					outputoffs += bufferoffs;
					done += bufferoffs;
					bufferoffs = 0;
				}
			}
		}
		for (std::size_t i = 0; i < outs.size(); ++i)
		{
			std::uint64_t len = 0;
			outs[i]->length(len);
			if (len != plan.sizes[i])
			{
				r.code = ResultCode::IO_ERROR;
				r.message = util::string_format("%s: size %u, expected %u", plan.files[i], len, plan.sizes[i]);
				return r;
			}
			if (std::error_condition const se = outs[i]->sync())
			{
				r.code = is_no_space(outs[i]->last_errno()) ? ResultCode::NO_SPACE : ResultCode::IO_ERROR;
				r.message = "fsync: " + se.message();
				return r;
			}
		}
		emit(opt, throttle, Stage::EXTRACT, 1.0, 1.0, plan.total_bytes, true, plan.total_bytes);
		r.code = ResultCode::OK;
		r.output_bytes = plan.total_bytes;
		r.sha1 = input.sha1().as_string();
		log(opt, "Extração de CD concluída");
	}
	catch (std::bad_alloc const &)
	{
		r.code = ResultCode::INTERNAL_ERROR;
		r.message = "out of memory";
	}
	catch (std::exception const &e)
	{
		r.code = ResultCode::INTERNAL_ERROR;
		r.message = e.what();
	}
	return r;
}

Result recompress(int in_fd, int out_fd, Options const &opt)
{
	std::lock_guard<std::mutex> lock(g_engine_mutex);
	ScopedStreamCapture cap(opt.log);
	Result r;
	configure_threads(opt);

	chd_file input;
	std::error_condition err = open_chd(in_fd, input);
	if (err)
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = "Error opening CHD file: " + err.message();
		return r;
	}
	bool const cd = has_cd_metadata(input);
	std::string perr;
	chd_codec_type compression[4];
	if (!check_hunk(opt.hunk_bytes, input.unit_bytes(), perr) || !parse_codecs(opt.codecs, cd, compression, perr))
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = perr;
		return r;
	}
	std::uint32_t const hunk_size = opt.hunk_bytes ? opt.hunk_bytes : input.hunk_bytes();
	if ((hunk_size % input.hunk_bytes()) && (input.hunk_bytes() % hunk_size))
	{
		r.code = ResultCode::INVALID_INPUT;
		r.message = util::string_format("hunk size %u is not a multiple or factor of the input hunk size %u", hunk_size, input.hunk_bytes());
		return r;
	}
	if (opt.codecs.empty())
	{
		auto const &defaults = get_compression_defaults(input); // função do chdman.cpp
		for (int i = 0; i < 4; ++i)
			compression[i] = defaults[i];
	}
	r.compression = codecs_string(compression);
	std::uint64_t const logical = input.logical_bytes();
	log(opt, util::string_format("Recompactação (chdman copy): %s, %u bytes, hunk %u -> %u, codecs %s, threads %d",
			cd ? "CD" : "DVD/dados", logical, input.hunk_bytes(), hunk_size, r.compression, effective_threads(opt)));

	auto outptr = open_output(out_fd, r);
	if (!outptr)
		return r;
	FdFile &out = *outptr;
	try
	{
		std::unique_ptr<cdrom_file> cdrom; // destruído depois do compressor
		auto chd = std::make_unique<CancellableChdCompressor>(input, opt.cancel);
		err = chd->create(std::move(outptr), logical, hunk_size, input.unit_bytes(), compression);
		if (err)
		{
			r.code = is_no_space(out.last_errno()) ? ResultCode::NO_SPACE : ResultCode::CHD_ERROR;
			r.message = "Error creating CHD file: " + err.message();
			return r;
		}
		// --- metadados, como no do_copy() ---
		std::vector<std::uint8_t> metadata;
		chd_metadata_tag metatag;
		std::uint8_t metaflags;
		std::uint32_t index = 0;
		bool redo_cd = false, cdda_swap = false;
		for (err = input.read_metadata(CHDMETATAG_WILDCARD, index++, metadata, metatag, metaflags); !err;
				err = input.read_metadata(CHDMETATAG_WILDCARD, index++, metadata, metatag, metaflags))
		{
			if (metatag == CDROM_OLD_METADATA_TAG || metatag == CDROM_TRACK_METADATA_TAG)
			{
				redo_cd = true;
				continue;
			}
			if (metatag == GDROM_OLD_METADATA_TAG)
			{
				cdda_swap = redo_cd = true;
				continue;
			}
			err = chd->write_metadata(metatag, CHDMETAINDEX_APPEND, metadata, metaflags);
			if (err)
			{
				r.code = ResultCode::CHD_ERROR;
				r.message = "Error writing cloned metadata: " + err.message();
				return r;
			}
		}
		if (redo_cd)
		{
			cdrom = std::make_unique<cdrom_file>(&input);
			cdrom_file::toc const &toc = cdrom->get_toc();
			err = cdrom_file::write_metadata(chd.get(), toc);
			if (err)
			{
				r.code = ResultCode::CHD_ERROR;
				r.message = "Error writing upgraded CD metadata: " + err.message();
				return r;
			}
			if (cdda_swap)
				chd->m_toc = &toc;
		}
		std::string const comp = r.compression;
		r = run_compression(*chd, out, opt, logical, cap);
		r.compression = comp;
	}
	catch (std::bad_alloc const &)
	{
		r.code = ResultCode::INTERNAL_ERROR;
		r.message = "out of memory";
	}
	catch (std::exception const &e)
	{
		r.code = ResultCode::INTERNAL_ERROR;
		r.message = e.what();
	}
	return r;
}

// Sem o mutex global: usa apenas objetos locais e pode rodar durante uma conversão.
std::string chd_info_json(int chd_fd)
{
	std::error_condition err;
	auto file = FdFile::from_fd(chd_fd, false, err);
	if (!file)
		return "{\"ok\":false,\"error\":\"" + json_escape(err.message()) + "\"}";
	std::uint64_t filelen = 0;
	file->length(filelen);
	chd_file chd;
	err = chd.open(std::move(file));
	if (err)
		return "{\"ok\":false,\"error\":\"" + json_escape(err.message()) + "\"}";

	std::string type = "raw";
	int tracks = 0;
	std::string meta;
	if (!chd.read_metadata(DVD_METADATA_TAG, 0, meta))
		type = "dvd";
	else if (!chd.read_metadata(GDROM_TRACK_METADATA_TAG, 0, meta) || !chd.read_metadata(GDROM_OLD_METADATA_TAG, 0, meta))
		type = "gd";
	else if (!chd.read_metadata(CDROM_TRACK_METADATA2_TAG, 0, meta) || !chd.read_metadata(CDROM_TRACK_METADATA_TAG, 0, meta)
			|| !chd.read_metadata(CDROM_OLD_METADATA_TAG, 0, meta))
		type = "cd";
	if (type == "cd" || type == "gd")
		for (chd_metadata_tag tag : { CDROM_TRACK_METADATA2_TAG, CDROM_TRACK_METADATA_TAG, GDROM_TRACK_METADATA_TAG })
			for (int i = 0; !chd.read_metadata(tag, i, meta); ++i)
				++tracks;

	// todos os metadados, como "tag: texto" (os binários aparecem só com o tamanho)
	std::string metalist;
	{
		std::vector<std::uint8_t> data;
		chd_metadata_tag tag;
		std::uint8_t flags;
		for (std::uint32_t i = 0; !chd.read_metadata(CHDMETATAG_WILDCARD, i, data, tag, flags); ++i)
		{
			char tagstr[5] = { char(tag >> 24), char(tag >> 16), char(tag >> 8), char(tag), 0 };
			bool printable = true;
			std::string text;
			for (std::uint8_t b : data)
			{
				if (b == 0)
					break;
				if (b < 0x20 || b > 0x7e)
				{
					printable = false;
					break;
				}
				text.push_back(char(b));
			}
			if (!metalist.empty())
				metalist += ',';
			metalist += '"' + json_escape(std::string(tagstr) + ": " + (printable ? text : util::string_format("(%u bytes)", data.size()))) + '"';
		}
	}
	std::string comp;
	for (int i = 0; i < 4; ++i)
	{
		chd_codec_type const c = chd.compression(i);
		if (c == CHD_CODEC_NONE)
			break;
		if (!comp.empty())
			comp += ", ";
		comp += chd_codec_list::codec_name(c);
	}
	return util::string_format(
			"{\"ok\":true,\"version\":%u,\"type\":\"%s\",\"tracks\":%d,\"logicalBytes\":%u,\"fileBytes\":%u,"
			"\"hunkBytes\":%u,\"unitBytes\":%u,\"compression\":\"%s\",\"sha1\":\"%s\",\"rawSha1\":\"%s\",\"metadata\":[%s]}",
			chd.version(), type, tracks, chd.logical_bytes(), filelen, chd.hunk_bytes(), chd.unit_bytes(),
			json_escape(comp), chd.sha1().as_string(), chd.raw_sha1().as_string(), metalist);
}

// Sem o mutex global e sem capturar std::cerr (que pode estar redirecionado por
// uma conversão em andamento): a análise de uma nova folha não espera a fila.
// Mensagens do parser vão para o log do sistema (osd_printf_error -> logcat).
std::string probe_toc_json(std::string const &toc_path)
{
	auto info = std::make_unique<cdrom_file::track_input_info>();
	auto toc = std::make_unique<cdrom_file::toc>();
	std::memset(toc.get(), 0, sizeof(cdrom_file::toc));
	std::error_condition const err = cdrom_file::parse_toc(toc_path, *toc, *info);
	if (err)
		return "{\"ok\":false,\"error\":\"" + json_escape(err.message()) + "\"}";
	std::ostringstream o;
	o << "{\"ok\":true,\"gdrom\":" << ((toc->flags & cdrom_file::CD_FLAG_GDROM) ? "true" : "false")
	  << ",\"numTracks\":" << toc->numtrks << ",\"tracks\":[";
	std::uint64_t frames = 0;
	bool missing = false;
	for (int i = 0; i < toc->numtrks; ++i)
	{
		auto const &t = toc->tracks[i];
		struct stat st;
		bool const exists = ::stat(info->track[i].fname.c_str(), &st) == 0;
		missing = missing || !exists;
		frames += t.frames;
		if (i)
			o << ',';
		o << "{\"number\":" << (i + 1)
		  << ",\"type\":\"" << json_escape(cdrom_file::get_type_string(t.trktype)) << '"'
		  << ",\"audio\":" << (t.trktype == cdrom_file::CD_TRACK_AUDIO ? "true" : "false")
		  << ",\"frames\":" << t.frames
		  << ",\"dataSize\":" << t.datasize
		  << ",\"subSize\":" << t.subsize
		  << ",\"file\":\"" << json_escape(info->track[i].fname) << '"'
		  << ",\"exists\":" << (exists ? "true" : "false") << '}';
	}
	o << "],\"totalFrames\":" << frames << ",\"missingFiles\":" << (missing ? "true" : "false") << '}';
	return o.str();
}

std::string core_version()
{
	return build_version;
}

} // namespace chdconv
