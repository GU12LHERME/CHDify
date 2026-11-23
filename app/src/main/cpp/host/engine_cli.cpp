// SPDX-License-Identifier: BSD-3-Clause
// Harness de linha de comando do motor (build de host) usado pelos testes
// automatizados. Exercita exatamente o mesmo código que o app chama via JNI,
// inclusive E/S por descritor de arquivo.
//
//   chdengine_cli probe <imagem>
//   chdengine_cli probetoc <arquivo.cue|.gdi|.iso>
//   chdengine_cli dvd <entrada.iso> <saida.chd> [--threads N] [--cancel-at F]
//   chdengine_cli cd <entrada.cue|.iso|.gdi> <saida.chd> [--threads N] [--hunk B] [--codecs a,b] [--cancel-at F] [--via-fd]
//   chdengine_cli verify <arquivo.chd>
//   chdengine_cli extractdvd <arquivo.chd> <saida.iso>
//   chdengine_cli plancd <arquivo.chd> <nome-base>
//   chdengine_cli extractcd <arquivo.chd> <pasta-saida> <nome-base>
//   chdengine_cli recompress <entrada.chd> <saida.chd> [--codecs a,b] [--hunk B]
//   chdengine_cli info <arquivo.chd>

#include "chd_engine.h"
#include "disc_probe.h"

#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <cctype>
#include <cstring>
#include <string>
#include <vector>

#include <fcntl.h>
#include <unistd.h>

using namespace chdconv;

static void print_result(Result const &r)
{
	std::printf("{\"code\":%d,\"message\":\"%s\",\"logicalBytes\":%llu,\"outputBytes\":%llu,\"hunkBytes\":%u,"
				"\"compression\":\"%s\",\"rawSha1\":\"%s\",\"sha1\":\"%s\",\"tracks\":%d}\n",
			int(r.code), r.message.c_str(), (unsigned long long)r.logical_bytes, (unsigned long long)r.output_bytes,
			r.hunk_bytes, r.compression.c_str(), r.raw_sha1.c_str(), r.sha1.c_str(), r.tracks);
}

int main(int argc, char **argv)
{
	if (argc < 3)
	{
		std::fprintf(stderr, "uso: %s probe|probetoc|dvd|cd|verify|info ...\n", argv[0]);
		return 64;
	}
	std::string const cmd = argv[1];
	int threads = 0;
	double cancel_at = -1.0;
	bool quiet = false;
	bool via_fd = false;
	unsigned hunk = 0;
	std::string codecs;
	for (int i = 2; i < argc; ++i)
	{
		if (!std::strcmp(argv[i], "--threads") && i + 1 < argc) threads = std::atoi(argv[++i]);
		else if (!std::strcmp(argv[i], "--cancel-at") && i + 1 < argc) cancel_at = std::atof(argv[++i]);
		else if (!std::strcmp(argv[i], "--quiet")) quiet = true;
		else if (!std::strcmp(argv[i], "--via-fd")) via_fd = true;
		else if (!std::strcmp(argv[i], "--hunk") && i + 1 < argc) hunk = unsigned(std::atoi(argv[++i]));
		else if (!std::strcmp(argv[i], "--codecs") && i + 1 < argc) codecs = argv[++i];
	}

	std::atomic<bool> cancel{ false };
	Options opt;
	opt.threads = threads;
	opt.hunk_bytes = hunk;
	opt.codecs = codecs;
	opt.cancel = &cancel;
	opt.progress_interval_ms = 500;
	opt.log = [&](std::string const &m) { if (!quiet) std::fprintf(stderr, "LOG %s\n", m.c_str()); };
	opt.progress = [&](Progress const &p) {
		if (!quiet)
			std::fprintf(stderr, "PROGRESS stage=%d %.2f%% ratio=%.3f %llu/%llu escrito=%llu\n", int(p.stage), 100.0 * p.fraction,
					p.ratio, (unsigned long long)p.done, (unsigned long long)p.total, (unsigned long long)p.written);
		if (cancel_at >= 0.0 && p.fraction >= cancel_at)
			cancel.store(true);
	};
	// com --cancel-at a verificação precisa de atualizações frequentes
	if (cancel_at >= 0.0)
		opt.progress_interval_ms = 1;

	if (cmd == "probe")
	{
		int fd = ::open(argv[2], O_RDONLY | O_CLOEXEC);
		if (fd < 0) { std::perror("open"); return 66; }
		std::printf("%s\n", probe_image_json(fd).c_str());
		::close(fd);
		return 0;
	}
	if (cmd == "probetoc")
	{
		std::printf("%s\n", probe_toc_json(argv[2]).c_str());
		return 0;
	}
	if (cmd == "info" || cmd == "verify")
	{
		int fd = ::open(argv[2], O_RDONLY | O_CLOEXEC);
		if (fd < 0) { std::perror("open"); return 66; }
		if (cmd == "info")
			std::printf("%s\n", chd_info_json(fd).c_str());
		else
		{
			Result const r = verify(fd, opt);
			print_result(r);
			::close(fd);
			return int(r.code);
		}
		::close(fd);
		return 0;
	}
	if (cmd == "plancd" && argc >= 4)
	{
		int fd = ::open(argv[2], O_RDONLY | O_CLOEXEC);
		if (fd < 0) { std::perror("open"); return 66; }
		std::printf("%s\n", plan_cd_extract_json(fd, argv[3]).c_str());
		::close(fd);
		return 0;
	}
	if ((cmd == "extractdvd" || cmd == "recompress") && argc >= 4)
	{
		int in = ::open(argv[2], O_RDONLY | O_CLOEXEC);
		if (in < 0) { std::perror("open"); return 66; }
		int out = ::open(argv[3], O_RDWR | O_CREAT | O_CLOEXEC, 0644);
		if (out < 0) { std::perror("open saida"); return 73; }
		Result const r = cmd == "extractdvd" ? extract_dvd(in, out, opt) : recompress(in, out, opt);
		::close(in);
		::close(out);
		if (r.code != ResultCode::OK)
			::unlink(argv[3]);
		print_result(r);
		return int(r.code);
	}
	if (cmd == "extractcd" && argc >= 5)
	{
		// mesmo fluxo do app: plano -> cria os arquivos -> extrai -> grava a folha
		int in = ::open(argv[2], O_RDONLY | O_CLOEXEC);
		if (in < 0) { std::perror("open"); return 66; }
		std::string const dir = argv[3], base = argv[4];
		std::string const plan = plan_cd_extract_json(in, base);
		if (plan.find("\"ok\":true") == std::string::npos) { std::printf("%s\n", plan.c_str()); return 2; }
		std::vector<std::string> files;
		std::size_t p = plan.find("\"files\":[") + 9;
		std::size_t const end = plan.find(']', p);
		while (p < end)
		{
			std::size_t const a = plan.find('"', p);
			if (a >= end) break;
			std::size_t b = a + 1;
			std::string name;
			while (plan[b] != '"') { if (plan[b] == '\\') ++b; name.push_back(plan[b]); ++b; }
			files.push_back(name);
			p = b + 1;
		}
		std::vector<int> fds;
		for (auto const &f : files)
			fds.push_back(::open((dir + "/" + f).c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0644));
		Result const r = extract_cd(in, base, fds, opt);
		for (int fd : fds) ::close(fd);
		// folha: texto exato do plano
		std::size_t const sp = plan.find("\"sheet\":\"") + 9;
		std::string sheet;
		for (std::size_t i = sp; i < plan.size() && plan[i] != '"'; ++i)
		{
			if (plan[i] == '\\') { ++i; sheet.push_back(plan[i] == 'n' ? '\n' : plan[i] == 't' ? '\t' : plan[i]); }
			else sheet.push_back(plan[i]);
		}
		std::string const ext = plan.find("\"sheetExt\":\"gdi\"") != std::string::npos ? ".gdi" : ".cue";
		if (r.code == ResultCode::OK)
		{
			FILE *f = std::fopen((dir + "/" + base + ext).c_str(), "wb");
			std::fwrite(sheet.data(), 1, sheet.size(), f);
			std::fclose(f);
		}
		::close(in);
		print_result(r);
		return int(r.code);
	}
	if ((cmd == "dvd" || cmd == "cd") && argc >= 4)
	{
		int out = ::open(argv[3], O_RDWR | O_CREAT | O_CLOEXEC, 0644);
		if (out < 0) { std::perror("open saida"); return 73; }
		Result r;
		if (cmd == "dvd")
		{
			int in = ::open(argv[2], O_RDONLY | O_CLOEXEC);
			if (in < 0) { std::perror("open entrada"); ::close(out); return 66; }
			r = convert_dvd(in, out, opt);
			::close(in);
		}
		else if (via_fd)
		{
			// Reproduz o que o app faz no Android: cada arquivo de trilha é aberto
			// (como o SAF entregaria um descritor); num diretório de trabalho ficam
			// uma cópia do .cue e "espelhos" esparsos de cada trilha, com o mesmo
			// tamanho e os primeiros 64 KiB reais (cabeçalhos WAVE). O parse_toc lê
			// os espelhos; os dados são lidos pelos descritores.
			std::string const src = argv[2];
			std::string const dir = src.substr(0, src.find_last_of('/') + 1);
			char tmpl[] = "/tmp/chdconv-XXXXXX";
			std::string const work = ::mkdtemp(tmpl);
			std::string const base = src.substr(src.find_last_of('/') + 1);
			std::vector<TrackFd> tracks;
			FILE *cue = std::fopen(src.c_str(), "rb");
			std::string text;
			if (cue) { char buf[4096]; size_t n; while ((n = std::fread(buf, 1, sizeof(buf), cue)) > 0) text.append(buf, n); std::fclose(cue); }
			FILE *copy = std::fopen((work + "/" + base).c_str(), "wb");
			std::fwrite(text.data(), 1, text.size(), copy);
			std::fclose(copy);
			// nomes referenciados: FILE "x" (.cue) ou 5ª coluna (.gdi)
			std::vector<std::string> names;
			bool const is_gdi = base.size() > 4 && base.substr(base.size() - 4) == ".gdi";
			if (is_gdi)
			{
				std::size_t ls = text.find('\n');
				while (ls != std::string::npos && ls + 1 < text.size())
				{
					std::size_t const le = text.find('\n', ls + 1);
					std::string line = text.substr(ls + 1, le == std::string::npos ? std::string::npos : le - ls - 1);
					std::vector<std::string> tok;
					for (std::size_t i = 0; i < line.size(); )
					{
						while (i < line.size() && std::isspace(static_cast<unsigned char>(line[i]))) ++i;
						if (i >= line.size()) break;
						if (line[i] == '"') { std::size_t const q = line.find('"', i + 1); tok.push_back(line.substr(i + 1, q - i - 1)); i = q + 1; }
						else { std::size_t const st = i; while (i < line.size() && !std::isspace(static_cast<unsigned char>(line[i]))) ++i; tok.push_back(line.substr(st, i - st)); }
					}
					if (tok.size() >= 5) names.push_back(tok[4]);
					ls = le;
				}
			}
			else
			{
				size_t fp = 0;
				while ((fp = text.find("FILE \"", fp)) != std::string::npos)
				{
					fp += 6;
					size_t const end = text.find('"', fp);
					names.push_back(text.substr(fp, end - fp));
					fp = end;
				}
			}
			for (std::string const &name : names)
			{
				int const fd = ::open((dir + name).c_str(), O_RDONLY | O_CLOEXEC);
				if (fd < 0)
					continue;
				std::string const mirror = work + "/" + name;
				int const m = ::open(mirror.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
				char head[65536];
				ssize_t const got = ::pread(fd, head, sizeof(head), 0);
				if (got > 0 && ::write(m, head, size_t(got)) != got) std::perror("write");
				if (::ftruncate(m, ::lseek(fd, 0, SEEK_END)) != 0) std::perror("ftruncate");
				::close(m);
				tracks.push_back({ mirror, fd });
			}
			std::string toc = work + "/" + base;
			auto mirror_of = [&](std::string const &real, std::string const &mirror) {
				int const fd = ::open(real.c_str(), O_RDONLY | O_CLOEXEC);
				if (fd < 0)
					return;
				int const m = ::open(mirror.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
				char head[65536];
				ssize_t const got = ::pread(fd, head, sizeof(head), 0);
				if (got > 0 && ::write(m, head, size_t(got)) != got) std::perror("write");
				if (::ftruncate(m, ::lseek(fd, 0, SEEK_END)) != 0) std::perror("ftruncate");
				::close(m);
				tracks.push_back({ mirror, fd });
			};
			if (tracks.empty() && base.size() > 4 && base.substr(base.size() - 4) == ".iso")
			{
				// ISO tratada como CD: o próprio arquivo é a única "trilha"
				mirror_of(src, toc);
			}
			r = convert_cd(toc, tracks, out, opt);
			for (auto const &t : tracks) ::close(t.fd);
			std::string const rm = "rm -rf '" + work + "'";
			if (std::system(rm.c_str()) != 0) { }
		}
		else
			r = convert_cd(argv[2], {}, out, opt);
		::close(out);
		if (r.code != ResultCode::OK)
			::unlink(argv[3]); // mesmo comportamento do app: não deixa arquivo parcial
		print_result(r);
		return int(r.code);
	}
	std::fprintf(stderr, "comando inválido\n");
	return 64;
}
