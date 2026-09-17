/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
import Drawer from '../EmailConnectorUserSettingConnectorsDrawer.vue';

/**
 * The connector list is filled by TWO paths, and the requirements must travel on
 * both. Only one of them was instrumented at first, and it was not the one that
 * runs on created(): the map stayed empty on the first render, so every connector
 * showed its credentials form - including the ones that ask for nothing. Found at
 * run, on the very connector the feature exists for.
 */
describe('connectors drawer, connection requirements', () => {

  const drawer = (requirements) => {
    const service = {
      getUserEmailConnectors: jest.fn().mockResolvedValue([]),
      getConnectionRequirements: jest.fn().mockResolvedValue(requirements),
    };
    const vm = {
      userEmailConnectors: [],
      connectionRequirements: {},
      featureName: 'email',
      $emailConnectorUserSettingService: service,
      $featureService: {isFeatureEnabled: jest.fn().mockResolvedValue(true)},
    };
    vm.getConnectionRequirements = Drawer.methods.getConnectionRequirements.bind(vm);
    return {vm, service};
  };

  it('reads them on the refresh path', async () => {
    const {vm, service} = drawer({'bluemind-sudo': false});

    await Drawer.methods.getUserEmailConnectors.call(vm);

    expect(service.getConnectionRequirements).toHaveBeenCalled();
  });

  it('reads them on the path that runs at creation, which serves the first render', async () => {
    const {vm, service} = drawer({'bluemind-sudo': false});

    await Drawer.methods.hideUserSetting.call(vm);

    expect(service.getConnectionRequirements).toHaveBeenCalled();
    expect(vm.connectionRequirements['bluemind-sudo']).toBe(false);
  });

  /**
   * A requirement nobody could read leaves every button opening its form. The
   * empty map is what the list item reads as "asks the user".
   */
  it('gives up to an empty map rather than to nothing', async () => {
    const {vm, service} = drawer(null);
    service.getConnectionRequirements.mockRejectedValue(new Error('offline'));

    await vm.getConnectionRequirements();

    expect(vm.connectionRequirements).toEqual({});
  });
});
