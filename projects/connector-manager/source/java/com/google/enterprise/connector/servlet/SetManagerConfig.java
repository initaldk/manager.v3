// Copyright 2006 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.enterprise.connector.servlet;

import com.google.enterprise.connector.logging.NDC;
import com.google.enterprise.connector.manager.Manager;

import java.io.PrintWriter;

/**
 * Returns the success response if successfully setting the manager config.
 */
public class SetManagerConfig extends ConnectorManagerUrlServlet {
  @Override
  protected void processDoPost(String connectorManagerUrl,
      String xmlBody, Manager manager, PrintWriter out) {
    NDC.append("Config Manager");
    SetManagerConfigHandler hdl =
        new SetManagerConfigHandler(manager, xmlBody, connectorManagerUrl);
    ServletUtil.writeResponse(out, hdl.getStatus());
  }
}
