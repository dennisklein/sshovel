# SPDX-FileCopyrightText: 2026 Dennis Klein
# SPDX-License-Identifier: GPL-3.0-or-later
# dnsmasq is installed at build time: at runtime this container sits only on
# the internal intranet network, which has no internet access.
FROM alpine:3
RUN apk add --no-cache dnsmasq
ENTRYPOINT ["dnsmasq", "-k", "--no-resolv", "--log-queries", "--log-facility=-", \
            "--address=/wiki.corp.test/10.77.0.20", \
            "--address=/api.corp.test/10.77.0.21"]
