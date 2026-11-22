// SPDX-License-Identifier: BSD-3-Clause
// Motor de conversão para CHD.
//
// Usa a biblioteca CHD oficial do MAME (chd.cpp, chdcodec.cpp, cdrom.cpp) e as
// classes de compressão do próprio chdman.cpp (incluído sem modificações em
// chd_engine.cpp). A diferença em relação ao chdman de linha de comando é apenas
// o laço de controle: aqui há callbacks de progresso, cancelamento cooperativo e
// E/S por descritor de arquivo (Storage Access Framework).
#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace chdconv {

enum class ResultCode : int
{
	OK = 0,
	CANCELLED = 1,
	INVALID_INPUT = 2,
	NO_SPACE = 3,
	IO_ERROR = 4,
	CHD_ERROR = 5,
	VERIFY_FAILED = 6,
	INTERNAL_ERROR = 7,
};

enum class Stage : int
{
	COMPRESS = 0,
	VERIFY = 1,
};

struct Progress
{
	Stage stage;
	double fraction;      // 0..1
	double ratio;         // tamanho comprimido / tamanho lido (0..1+)
	std::uint64_t done;   // bytes lógicos processados
	std::uint64_t total;  // bytes lógicos totais
	std::uint64_t written; // bytes já gravados no CHD (0 na verificação)
};

using ProgressFn = std::function<void(Progress const &)>;
using LogFn = std::function<void(std::string const &)>;

struct Options
{
	// Threads de compressão. 0 = automático (todos os núcleos). O valor é
	// repassado como "-np" do chdman (osd_num_processors); ver configure_threads().
	int threads = 0;
	// Tamanho do hunk em bytes (0 = padrão do chdman: 4096 em DVD, 19584 em CD).
	unsigned hunk_bytes = 0;
	// Codecs ("lzma,zlib,huff,flac" / "cdlz,cdzl,cdfl"...). Vazio = padrão do chdman.
	std::string codecs;
	std::atomic<bool> *cancel = nullptr;    // sinalizador de cancelamento
	ProgressFn progress;                    // chamado periodicamente (limitado internamente)
	LogFn log;                              // mensagens detalhadas
	unsigned progress_interval_ms = 150;
};

struct Result
{
	ResultCode code = ResultCode::INTERNAL_ERROR;
	std::string message;           // mensagem técnica (inglês/MAME) para log
	std::uint64_t logical_bytes = 0;
	std::uint64_t output_bytes = 0;
	unsigned hunk_bytes = 0;
	std::string compression;       // ex.: "lzma, zlib, huff, flac"
	std::string raw_sha1;
	std::string sha1;
	int tracks = 0;                // somente CD
};

// ISO de DVD (setores de 2048 bytes) -> CHD DVD (equivalente a "chdman createdvd").
Result convert_dvd(int in_fd, int out_fd, Options const &opt);

// Arquivo de trilha já aberto: "path" é o caminho que o parse_toc enxergará
// (no app, um arquivo-espelho esparso com o mesmo tamanho e o mesmo início do
// original) e "fd" é o descritor do arquivo real, de onde os dados são lidos.
struct TrackFd
{
	std::string path;
	int fd;
};

// CD a partir de .cue/.gdi/.toc/.iso (equivalente a "chdman createcd").
// A tabela de trilhas é interpretada pelo cdrom_file::parse_toc oficial. Se
// track_fds estiver vazio, os arquivos de trilha são abertos pelo caminho
// (como no chdman); caso contrário, são lidos pelos descritores informados.
Result convert_cd(std::string const &toc_path, std::vector<TrackFd> const &track_fds, int out_fd, Options const &opt);

// Verificação completa (equivalente a "chdman verify"): descomprime todos os
// hunks, recalcula o SHA-1 dos dados e o SHA-1 global com metadados.
Result verify(int chd_fd, Options const &opt);

// Informações de um CHD (versão, tipo, tamanho lógico, SHA-1) em JSON.
std::string chd_info_json(int chd_fd);

// Analisa a tabela de trilhas sem converter. Retorna JSON com as trilhas ou erro.
std::string probe_toc_json(std::string const &toc_path);

// Versão do núcleo (commit do MAME).
std::string core_version();

} // namespace chdconv
