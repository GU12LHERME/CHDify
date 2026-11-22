// SPDX-License-Identifier: BSD-3-Clause
#include "disc_probe.h"

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#include <sys/stat.h>
#include <unistd.h>

namespace chdconv {

namespace {

constexpr std::uint64_t SECTOR = 2048;
// Maior CD fisicamente possível (99 min): 445.500 setores.
constexpr std::uint64_t CD_MAX_SECTORS = 99ULL * 60 * 75;

struct Reader
{
	int fd;
	std::uint64_t size;
	// layout: tamanho do setor bruto e deslocamento dos dados do usuário
	std::uint32_t sector_size = 2048;
	std::uint32_t data_offset = 0;

	bool read_raw(std::uint64_t off, void *dst, std::size_t len) const
	{
		if (off + len > size)
			return false;
		auto *p = static_cast<std::uint8_t *>(dst);
		std::size_t got = 0;
		while (got < len)
		{
			ssize_t const r = ::pread(fd, p + got, len - got, off_t(off + got));
			if (r < 0 && errno == EINTR)
				continue;
			if (r <= 0)
				return false;
			got += std::size_t(r);
		}
		return true;
	}

	// lê um setor lógico de 2048 bytes (dados do usuário)
	bool read_sector(std::uint64_t lba, std::uint8_t *dst) const
	{
		return read_raw(lba * sector_size + data_offset, dst, SECTOR);
	}
};

std::uint32_t le32(std::uint8_t const *p) { return p[0] | (p[1] << 8) | (p[2] << 16) | (std::uint32_t(p[3]) << 24); }
std::uint32_t be32(std::uint8_t const *p) { return (std::uint32_t(p[0]) << 24) | (p[1] << 16) | (p[2] << 8) | p[3]; }

std::string trim(std::string s)
{
	auto notspace = [](unsigned char c) { return c > ' '; };
	s.erase(s.begin(), std::find_if(s.begin(), s.end(), notspace));
	s.erase(std::find_if(s.rbegin(), s.rend(), notspace).base(), s.end());
	return s;
}

std::string printable(std::uint8_t const *p, std::size_t n)
{
	std::string s;
	for (std::size_t i = 0; i < n; ++i)
		s.push_back((p[i] >= 0x20 && p[i] < 0x7f) ? char(p[i]) : ' ');
	return trim(s);
}

std::string esc(std::string const &s)
{
	std::string o;
	for (char c : s)
	{
		if (c == '"' || c == '\\') { o.push_back('\\'); o.push_back(c); }
		else if (static_cast<unsigned char>(c) < 0x20) o.push_back(' ');
		else o.push_back(c);
	}
	return o;
}

struct DirEntry
{
	std::string name;
	std::uint32_t lba;
	std::uint32_t size;
	bool dir;
};

// Lista o diretório raiz ISO9660 (somente o primeiro nível).
std::vector<DirEntry> list_root(Reader const &rd, std::uint8_t const *pvd)
{
	std::vector<DirEntry> out;
	std::uint8_t const *root = pvd + 156;
	std::uint32_t const lba = le32(root + 2);
	std::uint32_t const len = std::min<std::uint32_t>(le32(root + 10), 64 * SECTOR);
	std::vector<std::uint8_t> sec(SECTOR);
	for (std::uint32_t s = 0; s * SECTOR < len; ++s)
	{
		if (!rd.read_sector(lba + s, sec.data()))
			break;
		std::uint32_t pos = 0;
		while (pos < SECTOR)
		{
			std::uint8_t const reclen = sec[pos];
			if (reclen == 0 || pos + reclen > SECTOR)
				break;
			std::uint8_t const namelen = sec[pos + 32];
			if (33u + namelen <= reclen && namelen > 0)
			{
				std::string name(reinterpret_cast<char const *>(&sec[pos + 33]), namelen);
				if (!(namelen == 1 && (name[0] == 0 || name[0] == 1)))
				{
					auto semi = name.find(';');
					if (semi != std::string::npos)
						name.resize(semi);
					if (!name.empty() && name.back() == '.')
						name.pop_back();
					for (auto &c : name)
						c = char(std::toupper(static_cast<unsigned char>(c)));
					out.push_back({ name, le32(&sec[pos + 2]), le32(&sec[pos + 10]), (sec[pos + 25] & 2) != 0 });
				}
			}
			pos += reclen;
		}
	}
	return out;
}

} // anonymous namespace

std::string probe_image_json(int fd)
{
	struct stat st;
	if (fd < 0 || ::fstat(fd, &st) != 0)
		return "{\"ok\":false,\"error\":\"fstat\"}";
	if (!S_ISREG(st.st_mode))
		return "{\"ok\":false,\"error\":\"not_regular_file\"}";

	Reader rd{ fd, std::uint64_t(st.st_size) };
	std::ostringstream o;
	o << "{\"ok\":true,\"size\":" << rd.size;

	// --- layout de setores ---
	std::string layout = "unknown";
	static std::uint8_t const sync[12] = { 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0 };
	std::uint8_t head[16] = {};
	bool const has_head = rd.read_raw(0, head, 16);
	if (has_head && std::memcmp(head, sync, 12) == 0 && rd.size % 2352 == 0)
	{
		layout = "2352";
		rd.sector_size = 2352;
		rd.data_offset = (head[15] == 2) ? 24 : 16; // modo 2 forma 1 : modo 1
	}
	else if (rd.size % 2048 == 0)
		layout = "2048";
	else if (rd.size % 2352 == 0)
		layout = "2352";
	else if (rd.size % 2336 == 0)
		layout = "2336";
	o << ",\"layout\":\"" << layout << "\"";
	std::uint64_t const sectors = rd.size / rd.sector_size;
	o << ",\"sectors\":" << sectors;
	o << ",\"exceedsCd\":" << ((layout == "2048" && sectors > CD_MAX_SECTORS) ? "true" : "false");

	// --- ISO9660 / UDF ---
	bool iso = false, udf = false;
	std::vector<std::uint8_t> sec(SECTOR), pvd;
	if (layout == "2048" || layout == "2352")
	{
		for (std::uint64_t lba = 16; lba < 16 + 32 && lba < sectors; ++lba)
		{
			if (!rd.read_sector(lba, sec.data()))
				break;
			if (std::memcmp(&sec[1], "CD001", 5) == 0)
			{
				if (sec[0] == 1 && pvd.empty())
					pvd = sec;
				iso = true;
				if (sec[0] == 255)
					continue;
			}
			else if (std::memcmp(&sec[1], "BEA01", 5) == 0 || std::memcmp(&sec[1], "NSR02", 5) == 0
					|| std::memcmp(&sec[1], "NSR03", 5) == 0)
			{
				if (sec[1] == 'N')
					udf = true;
			}
			else if (std::memcmp(&sec[1], "TEA01", 5) == 0)
				break;
			else if (!iso && lba > 17)
				break;
		}
	}
	o << ",\"iso9660\":" << (iso ? "true" : "false") << ",\"udf\":" << (udf ? "true" : "false");

	std::string platform = "unknown", boot;
	if (!pvd.empty())
	{
		std::string const system = printable(&pvd[8], 32);
		std::string const volume = printable(&pvd[40], 32);
		std::uint32_t const vss = le32(&pvd[80]);
		o << ",\"systemId\":\"" << esc(system) << "\",\"volumeId\":\"" << esc(volume) << "\",\"volumeSectors\":" << vss;
		o << ",\"truncated\":" << ((std::uint64_t(vss) > sectors + 2 /* tolerância */) ? "true" : "false");

		auto entries = list_root(rd, pvd.data());
		auto find = [&](char const *n) -> DirEntry const * {
			for (auto const &e : entries)
				if (e.name == n)
					return &e;
			return nullptr;
		};
		if (auto const *cnf = find("SYSTEM.CNF"); cnf && !cnf->dir && cnf->size < 4096)
		{
			std::vector<std::uint8_t> buf(SECTOR * 2, 0);
			std::uint32_t const n = std::min<std::uint32_t>(cnf->size, SECTOR * 2);
			bool ok = rd.read_sector(cnf->lba, buf.data());
			if (ok && n > SECTOR)
				ok = rd.read_sector(cnf->lba + 1, buf.data() + SECTOR);
			if (ok)
			{
				std::string text(reinterpret_cast<char const *>(buf.data()), n);
				std::istringstream lines(text);
				std::string line;
				while (std::getline(lines, line))
				{
					std::string const t = trim(line);
					std::string upper = t;
					for (auto &c : upper) c = char(std::toupper(static_cast<unsigned char>(c)));
					if (upper.rfind("BOOT2", 0) == 0) { platform = "ps2"; boot = t; break; }
					if (upper.rfind("BOOT", 0) == 0) { platform = "ps1"; boot = t; }
				}
			}
		}
		if (platform == "unknown")
		{
			auto const *pspdir = find("PSP_GAME");
			if ((pspdir && pspdir->dir) || find("UMD_DATA.BIN") || system.rfind("PSP GAME", 0) == 0)
				platform = "psp";
			else if (system.rfind("PLAYSTATION", 0) == 0)
				platform = "playstation";
		}
	}

	// --- assinaturas fora do ISO9660 ---
	if (platform == "unknown" && rd.size > 0x40)
	{
		std::uint8_t h[0x40];
		if (rd.read_raw(0, h, sizeof(h)))
		{
			if (be32(h + 0x1c) == 0xC2339F3Du)
				platform = "gamecube";
			else if (be32(h + 0x18) == 0x5D1C9EA3u)
				platform = "wii";
			else if (std::memcmp(h, "SEGA SEGASATURN", 15) == 0)
				platform = "saturn";
			else if (std::memcmp(h, "SEGADISCSYSTEM", 14) == 0 || std::memcmp(h + 16, "SEGADISCSYSTEM", 14) == 0)
				platform = "segacd";
			else if (std::memcmp(h, "SEGA SEGAKATANA", 15) == 0)
				platform = "dreamcast";
			else if (std::memcmp(h + 0x10, "SEGA SEGASATURN", 15) == 0)
				platform = "saturn";
		}
		std::uint8_t x[20];
		if (platform == "unknown" && rd.read_raw(0x10000, x, 20) && std::memcmp(x, "MICROSOFT*XBOX*MEDIA", 20) == 0)
			platform = "xbox";
	}

	o << ",\"platform\":\"" << platform << "\",\"boot\":\"" << esc(boot) << "\"}";
	return o.str();
}

} // namespace chdconv
