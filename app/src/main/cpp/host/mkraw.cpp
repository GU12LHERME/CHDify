// SPDX-License-Identifier: BSD-3-Clause
// Ferramenta de host para benchmarks: converte uma ISO de 2048 bytes/setor em
// uma trilha MODE1/2352 bruta, com sincronismo, cabeçalho MSF, EDC e ECC
// válidos (ECC gerado pelo próprio cdrom_file::ecc_generate do MAME), como num
// disco real. Uso: mkraw <entrada.iso> <saida.bin>
#include "cdrom.h"

#include <cstdint>
#include <cstdio>
#include <cstring>

static uint32_t edc_table[256];

static void edc_init()
{
	for (uint32_t i = 0; i < 256; i++)
	{
		uint32_t e = i;
		for (int j = 0; j < 8; j++)
			e = (e >> 1) ^ ((e & 1) ? 0xD8018001u : 0);
		edc_table[i] = e;
	}
}

int main(int argc, char **argv)
{
	if (argc != 3)
	{
		std::fprintf(stderr, "uso: %s entrada.iso saida.bin\n", argv[0]);
		return 64;
	}
	edc_init();
	FILE *in = std::fopen(argv[1], "rb");
	FILE *out = std::fopen(argv[2], "wb");
	if (!in || !out)
		return 66;
	uint8_t sec[2352];
	uint32_t lba = 0;
	auto bcd = [](uint32_t v) { return uint8_t(((v / 10) << 4) | (v % 10)); };
	while (std::fread(sec + 16, 1, 2048, in) == 2048)
	{
		static const uint8_t sync[12] = { 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0 };
		std::memcpy(sec, sync, 12);
		uint32_t const a = lba + 150;
		sec[12] = bcd(a / 4500);
		sec[13] = bcd((a / 75) % 60);
		sec[14] = bcd(a % 75);
		sec[15] = 1;
		uint32_t edc = 0;
		for (int i = 0; i < 2064; i++)
			edc = (edc >> 8) ^ edc_table[(edc ^ sec[i]) & 0xff];
		sec[2064] = edc & 0xff;
		sec[2065] = (edc >> 8) & 0xff;
		sec[2066] = (edc >> 16) & 0xff;
		sec[2067] = (edc >> 24) & 0xff;
		std::memset(sec + 2068, 0, 8);
		cdrom_file::ecc_generate(sec);
		if (!cdrom_file::ecc_verify(sec))
			return 70;
		std::fwrite(sec, 1, 2352, out);
		lba++;
	}
	std::fclose(in);
	std::fclose(out);
	std::printf("%u setores\n", lba);
	return 0;
}
