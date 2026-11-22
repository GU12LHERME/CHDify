// SPDX-License-Identifier: BSD-3-Clause
// Análise de imagens de disco antes da conversão.
#pragma once

#include <string>

namespace chdconv {

// Lê cabeçalhos da imagem (ISO9660, UDF, assinaturas de plataformas) e
// devolve um JSON com os fatos encontrados. A decisão de modo (CD/DVD) e as
// mensagens para o usuário ficam na camada Kotlin.
std::string probe_image_json(int fd);

} // namespace chdconv
