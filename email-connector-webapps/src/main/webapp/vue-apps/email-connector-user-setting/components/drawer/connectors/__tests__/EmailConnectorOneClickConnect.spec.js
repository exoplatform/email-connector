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
import { shallowMount } from '@vue/test-utils';
import ListItem from '../EmailConnectorUserSettingConnectorsDrawerListItem.vue';

/**
 * A connector whose provider asks the user for nothing connects in one click: no
 * drawer, no form. What it must NOT do is connect silently when nobody could say
 * whether the user has something to supply - an absent requirement, a connector
 * naming no provider, a failed fetch. Those all open the form, which is what
 * every connector did before this existed.
 */
describe('one-click connect, email', () => {

  const connector = (overrides) => Object.assign({
    id: 7,
    name: 'BlueMind',
    authProviderName: 'bluemind-sudo',
    userConnected: false,
    canConnect: true,
  }, overrides);

  /** The component's own logic, exercised without mounting Vuetify. */
  const item = (userEmailConnector, connectionRequirements, service) => {
    const vm = {
      userEmailConnector,
      connectionRequirements,
      connecting: false,
      emitted: [],
      $emailConnectorUserSettingService: service,
      $t: key => key,
      $root: {$emit: (...args) => vm.emitted.push(args)},
    };
    vm.requiresUserAction = ListItem.computed.requiresUserAction.call(vm);
    return vm;
  };

  it('connects through the provider without opening the form', async () => {
    const connectThroughProvider = jest.fn().mockResolvedValue();
    const vm = item(connector(), {'bluemind-sudo': false}, {connectThroughProvider});

    expect(vm.requiresUserAction).toBe(false);
    await ListItem.methods.connect.call(vm);

    expect(connectThroughProvider).toHaveBeenCalledWith(7);
    // The credentials drawer is opened by this event; nothing must have asked for it.
    expect(vm.emitted.filter(e => e[0] === 'open-user-setting-drawer')).toHaveLength(0);
  });

  it('opens the form whenever nobody said the provider asks for nothing', () => {
    // A provider declaring it asks, a connector naming none, requirements that
    // could not be fetched: three ways to know nothing, one behaviour.
    expect(item(connector(), {'bluemind-sudo': true}, {}).requiresUserAction).toBe(true);
    expect(item(connector(), {}, {}).requiresUserAction).toBe(true);
    expect(item(connector({authProviderName: null}), {'bluemind-sudo': false}, {}).requiresUserAction).toBe(true);

    const connectThroughProvider = jest.fn();
    const vm = item(connector(), {}, {connectThroughProvider});
    ListItem.methods.connect.call(vm);

    expect(connectThroughProvider).not.toHaveBeenCalled();
    expect(vm.emitted.filter(e => e[0] === 'open-user-setting-drawer')).toHaveLength(1);
  });

  it('sends a connected account to the disconnect flow, whatever its provider', () => {
    const connectThroughProvider = jest.fn();
    const vm = item(connector({userConnected: true}), {'bluemind-sudo': false}, {connectThroughProvider});

    ListItem.methods.connect.call(vm);

    expect(connectThroughProvider).not.toHaveBeenCalled();
    expect(vm.emitted.filter(e => e[0] === 'open-user-setting-disconnect-drawer')).toHaveLength(1);
  });

  /**
   * Editing is retyping an address and a password, and a provider-backed
   * connection has neither: the drawer would open on a form nobody can fill.
   * <p>
   * Pinned on the RENDERED button rather than on the flag: the flag being right
   * says nothing about the template using it, and it is the template that decides
   * what the user sees.
   */
  it('renders no edit button on a connection the platform authenticates', () => {
    const wrapper = shallowMount(ListItem, {
      propsData: {
        userEmailConnector: connector({userConnected: true}),
        connectionRequirements: {'bluemind-sudo': false},
      },
      mocks: {$t: key => key},
    });

    // Le titre du bouton, pas son icone : shallowMount remplace <v-icon> par un
    // stub dont le texte disparait, et un selecteur dessus passe a vide.
    expect(wrapper.html()).not.toContain('button.edit.tooltip');
  });

  /** A typed connection keeps its edit button - there, retyping is the point. */
  it('renders the edit button on a typed connection', () => {
    const wrapper = shallowMount(ListItem, {
      propsData: {
        userEmailConnector: connector({userConnected: true}),
        connectionRequirements: {'bluemind-sudo': true},
      },
      mocks: {$t: key => key},
    });

    expect(wrapper.html()).toContain('button.edit.tooltip');
  });
});