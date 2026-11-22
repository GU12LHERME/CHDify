// SPDX-License-Identifier: BSD-3-Clause
// Adaptador util::random_read_write sobre um descritor de arquivo POSIX.
//
// No Android, os arquivos escolhidos pelo Storage Access Framework chegam como
// ParcelFileDescriptor. Este adaptador permite que a biblioteca CHD do MAME leia
// e grave diretamente nesses descritores (pread/pwrite com offsets de 64 bits),
// sem copiar o arquivo para o armazenamento interno e sem limite de 4 GB.
#pragma once

#include "ioprocs.h"

#include <atomic>
#include <cstdint>
#include <system_error>

namespace chdconv {

class FdFile final : public util::random_read_write
{
public:
	// Duplica o descritor recebido; o original continua pertencendo a quem chamou.
	static std::unique_ptr<FdFile> from_fd(int fd, bool writable, std::error_condition &err);

	~FdFile() override;

	// util::read_stream
	std::error_condition read_some(void *buffer, std::size_t length, std::size_t &actual) noexcept override;
	// util::write_stream
	std::error_condition finalize() noexcept override;
	std::error_condition flush() noexcept override;
	std::error_condition write_some(void const *buffer, std::size_t length, std::size_t &actual) noexcept override;
	// util::random_access
	std::error_condition seek(std::int64_t offset, int whence) noexcept override;
	std::error_condition tell(std::uint64_t &result) noexcept override;
	std::error_condition length(std::uint64_t &result) noexcept override;
	// util::random_read / random_write
	std::error_condition read_some_at(std::uint64_t offset, void *buffer, std::size_t length, std::size_t &actual) noexcept override;
	std::error_condition write_some_at(std::uint64_t offset, void const *buffer, std::size_t length, std::size_t &actual) noexcept override;

	// Força a gravação física (fsync). Usado ao concluir o CHD.
	std::error_condition sync() noexcept;

	// Último errno observado em uma falha de E/S (ENOSPC, EIO...). 0 se nenhum.
	int last_errno() const noexcept { return m_last_errno.load(); }
	std::uint64_t bytes_written() const noexcept { return m_bytes_written.load(); }
	int fd() const noexcept { return m_fd; }

private:
	FdFile(int fd, bool writable) noexcept : m_fd(fd), m_writable(writable) { }
	std::error_condition fail(int e) noexcept;

	int m_fd;
	bool m_writable;
	std::uint64_t m_pos = 0;
	std::atomic<int> m_last_errno{0};
	std::atomic<std::uint64_t> m_bytes_written{0};
};

} // namespace chdconv
