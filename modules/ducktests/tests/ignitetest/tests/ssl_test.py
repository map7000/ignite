# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""
This module contains ssl tests.
"""
from ignitetest.services.ignite import IgniteService
from ignitetest.services.ignite_app import IgniteApplicationService
from ignitetest.services.utils.control_utility import ControlUtility
from ignitetest.services.utils.ignite_configuration import IgniteConfiguration, SslContextFactory
from ignitetest.services.utils.ignite_configuration.connector_configuration import ConnectorConfiguration
from ignitetest.services.utils.ignite_configuration.discovery import from_ignite_cluster
from ignitetest.utils import ignite_versions, cluster
from ignitetest.utils.ignite_test import IgniteTest
from ignitetest.utils.version import DEV_BRANCH, IgniteVersion, LATEST_2_9, LATEST_2_8


# pylint: disable=W0223
class SslTest(IgniteTest):
    """
    Ssl test.
    """
    @cluster(num_nodes=3)
    @ignite_versions(str(DEV_BRANCH), str(LATEST_2_9), str(LATEST_2_8))
    def test_ssl_connection(self, ignite_version):
        """
        Test that IgniteService, IgniteApplicationService correctly start and stop with ssl configurations.
        And check ControlUtility with ssl arguments.
        """
        server_ssl = SslContextFactory(self.test_context.globals, key_store_jks="server.jks")

        server_configuration = IgniteConfiguration(
            version=IgniteVersion(ignite_version), ssl_context_factory=server_ssl,
            connector_configuration=ConnectorConfiguration(ssl_enabled=True, ssl_context_factory=server_ssl))

        ignite = IgniteService(self.test_context, server_configuration, num_nodes=2,
                               startup_timeout_sec=180)

        client_ssl = SslContextFactory(self.test_context.globals, key_store_jks="client.jks")

        client_configuration = server_configuration._replace(client_mode=True,
                                                             discovery_spi=from_ignite_cluster(ignite),
                                                             ssl_context_factory=client_ssl)

        app = IgniteApplicationService(
            self.test_context,
            client_configuration,
            java_class_name="org.apache.ignite.internal.ducktest.tests.smoke_test.SimpleApplication")

        control_utility = ControlUtility(cluster=ignite, key_store_jks="admin.jks")

        ignite.start()
        app.start()

        control_utility.cluster_state()

        app.stop()
        ignite.stop()
