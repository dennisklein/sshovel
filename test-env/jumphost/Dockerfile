# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
FROM alpine:3
RUN apk add --no-cache openssh-server iproute2 \
 && adduser -D -s /bin/sh tester \
 && echo 'tester:*' | chpasswd -e
COPY sshd_config /etc/ssh/sshd_config
COPY entrypoint.sh /entrypoint.sh
EXPOSE 22
ENTRYPOINT ["/entrypoint.sh"]
