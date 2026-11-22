// SPDX-License-Identifier: BSD-3-Clause
#include "fd_file.h"

#include <cerrno>
#include <cstdio>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace chdconv {

std::unique_ptr<FdFile> FdFile::from_fd(int fd, bool writable, std::error_condition &err)
{
	err.clear();
	if (fd < 0)
	{
		err = std::errc::bad_file_descriptor;
		return nullptr;
	}
	int const dupfd = ::fcntl(fd, F_DUPFD_CLOEXEC, 0);
	if (dupfd < 0)
	{
		err = std::error_condition(errno, std::generic_category());
		return nullptr;
	}
	return std::unique_ptr<FdFile>(new FdFile(dupfd, writable));
}

FdFile::~FdFile()
{
	if (m_fd >= 0)
		::close(m_fd);
}

std::error_condition FdFile::fail(int e) noexcept
{
	m_last_errno.store(e);
	return std::error_condition(e, std::generic_category());
}

std::error_condition FdFile::read_some_at(std::uint64_t offset, void *buffer, std::size_t length, std::size_t &actual) noexcept
{
	actual = 0;
	auto *dst = static_cast<std::uint8_t *>(buffer);
	while (actual < length)
	{
		ssize_t const r = ::pread64(m_fd, dst + actual, length - actual, off64_t(offset + actual));
		if (r < 0)
		{
			if (errno == EINTR)
				continue;
			return fail(errno);
		}
		if (r == 0)
			break; // fim do arquivo
		actual += std::size_t(r);
	}
	return std::error_condition();
}

std::error_condition FdFile::write_some_at(std::uint64_t offset, void const *buffer, std::size_t length, std::size_t &actual) noexcept
{
	actual = 0;
	if (!m_writable)
		return fail(EBADF);
	auto const *src = static_cast<std::uint8_t const *>(buffer);
	while (actual < length)
	{
		ssize_t const w = ::pwrite64(m_fd, src + actual, length - actual, off64_t(offset + actual));
		if (w < 0)
		{
			if (errno == EINTR)
				continue;
			return fail(errno);
		}
		if (w == 0)
			return fail(ENOSPC);
		actual += std::size_t(w);
	}
	m_bytes_written.fetch_add(actual);
	return std::error_condition();
}

std::error_condition FdFile::read_some(void *buffer, std::size_t length, std::size_t &actual) noexcept
{
	std::error_condition const err = read_some_at(m_pos, buffer, length, actual);
	m_pos += actual;
	return err;
}

std::error_condition FdFile::write_some(void const *buffer, std::size_t length, std::size_t &actual) noexcept
{
	std::error_condition const err = write_some_at(m_pos, buffer, length, actual);
	m_pos += actual;
	return err;
}

std::error_condition FdFile::seek(std::int64_t offset, int whence) noexcept
{
	std::uint64_t base = 0;
	switch (whence)
	{
	case SEEK_SET: base = 0; break;
	case SEEK_CUR: base = m_pos; break;
	case SEEK_END:
		if (std::error_condition err = length(base))
			return err;
		break;
	default:
		return std::errc::invalid_argument;
	}
	if (offset < 0 && std::uint64_t(-offset) > base)
		return std::errc::invalid_argument;
	m_pos = base + offset;
	return std::error_condition();
}

std::error_condition FdFile::tell(std::uint64_t &result) noexcept
{
	result = m_pos;
	return std::error_condition();
}

std::error_condition FdFile::length(std::uint64_t &result) noexcept
{
	struct stat64 st;
	if (::fstat64(m_fd, &st) != 0)
		return fail(errno);
	result = std::uint64_t(st.st_size);
	return std::error_condition();
}

std::error_condition FdFile::finalize() noexcept
{
	return std::error_condition();
}

std::error_condition FdFile::flush() noexcept
{
	return std::error_condition();
}

std::error_condition FdFile::sync() noexcept
{
	if (m_writable && ::fsync(m_fd) != 0 && errno != EINVAL && errno != EROFS)
		return fail(errno);
	return std::error_condition();
}

} // namespace chdconv
