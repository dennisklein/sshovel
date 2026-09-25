// SPDX-FileCopyrightText: 2026 Dennis Klein
// SPDX-License-Identifier: GPL-3.0-or-later

package engine_test

import (
	"encoding/json"

	"github.com/dennisklein/sshovel/core/config"
)

func jsonOf(p *config.Profile) ([]byte, error) { return json.Marshal(p) }
