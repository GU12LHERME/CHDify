// Shim mínimo: osdlib_unix.cpp do MAME inclui SDL apenas para a área de
// transferência. Este app não usa SDL; as funções abaixo são inertes.
#pragma once
#include <cstdlib>
inline bool SDL_HasClipboardText() { return false; }
inline char *SDL_GetClipboardText() { return nullptr; }
inline int SDL_SetClipboardText(const char *) { return -1; }
inline const char *SDL_GetError() { return "SDL indisponível"; }
inline void SDL_free(void *p) { std::free(p); }
