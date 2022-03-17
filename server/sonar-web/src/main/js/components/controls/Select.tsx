/*
 * SonarQube
 * Copyright (C) 2009-2022 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
import styled from '@emotion/styled';
import * as React from 'react';
import ReactSelect, { GroupTypeBase, IndicatorProps, Props, StylesConfig } from 'react-select';
import { MultiValueRemoveProps } from 'react-select/src/components/MultiValue';
import { colors, others, sizes, zIndexes } from '../../app/theme';
import { ClearButton } from './buttons';

const ArrowSpan = styled.span`
  border-color: #999 transparent transparent;
  border-style: solid;
  border-width: 4px 4px 2px;
  display: inline-block;
  height: 0;
  width: 0;
`;

export default class Select<
  Option,
  IsMulti extends boolean = false,
  Group extends GroupTypeBase<Option> = GroupTypeBase<Option>
> extends React.PureComponent<Props<Option, IsMulti, Group>> {
  dropdownIndicator({ innerProps }: IndicatorProps<Option, IsMulti, Group>) {
    return <ArrowSpan {...innerProps} />;
  }

  clearIndicator({ innerProps }: IndicatorProps<Option, IsMulti, Group>) {
    return (
      <ClearButton
        className="button-tiny spacer-left spacer-right text-middle"
        iconProps={{ size: 12 }}
        {...innerProps}
      />
    );
  }

  multiValueRemove(props: MultiValueRemoveProps<Option, Group>) {
    return <div {...props.innerProps}>×</div>;
  }

  render() {
    return (
      <ReactSelect
        {...this.props}
        styles={selectStyle<Option, IsMulti, Group>()}
        components={{
          ...this.props.components,
          DropdownIndicator: this.dropdownIndicator,
          ClearIndicator: this.clearIndicator,
          MultiValueRemove: this.multiValueRemove
        }}
      />
    );
  }
}

export function selectStyle<
  Option,
  IsMulti extends boolean,
  Group extends GroupTypeBase<Option>
>(): StylesConfig<Option, IsMulti, Group> {
  return {
    container: () => ({
      position: 'relative',
      display: 'inline-block',
      verticalAlign: 'middle',
      fontSize: '12px',
      textAlign: 'left',
      width: '100%'
    }),
    control: () => ({
      position: 'relative',
      display: 'flex',
      width: '100%',
      minHeight: `${sizes.controlHeight}`,
      lineHeight: `calc(${sizes.controlHeight} - 2px)`,
      border: `1px solid ${colors.gray80}`,
      borderCollapse: 'separate',
      borderRadius: '2px',
      backgroundColor: '#fff',
      boxSizing: 'border-box',
      color: `${colors.baseFontColor}`,
      cursor: 'default',
      outline: 'none'
    }),
    singleValue: () => ({
      bottom: 0,
      left: 0,
      lineHeight: '23px',
      paddingLeft: '8px',
      paddingRight: '24px',
      position: 'absolute',
      right: 0,
      top: 0,
      maxWidth: '100%',
      overflow: 'hidden',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap'
    }),
    valueContainer: (_provided, state) => {
      if (state.hasValue && state.isMulti) {
        return {
          lineHeight: '23px',
          paddingLeft: '1px'
        };
      }

      return {
        bottom: 0,
        left: 0,
        lineHeight: '23px',
        paddingLeft: '8px',
        paddingRight: '24px',
        position: 'absolute',
        right: 0,
        top: 0,
        maxWidth: '100%',
        overflow: 'hidden',
        textOverflow: 'ellipsis',
        whiteSpace: 'nowrap'
      };
    },
    indicatorsContainer: () => ({
      position: 'relative',
      cursor: 'pointer',
      textAlign: 'end',
      verticalAlign: 'middle',
      width: '20px',
      paddingRight: '5px',
      flex: 1
    }),
    multiValue: () => ({
      display: 'inline-block',
      backgroundColor: 'rgba(0, 126, 255, 0.08)',
      borderRadius: '2px',
      border: '1px solid rgba(0, 126, 255, 0.24)',
      color: '#333',
      maxWidth: '200px',
      fontSize: '12px',
      lineHeight: '14px',
      margin: '1px 4px 1px 1px',
      verticalAlign: 'top'
    }),
    multiValueLabel: () => ({
      display: 'inline-block',
      cursor: 'default',
      padding: '2px 5px',
      overflow: 'hidden',
      marginRight: 'auto',
      maxWidth: 'calc(200px - 28px)',
      textOverflow: 'ellipsis',
      whiteSpace: 'nowrap',
      verticalAlign: 'middle'
    }),
    multiValueRemove: () => ({
      order: '-1',
      cursor: 'pointer',
      borderLeft: '1px solid rgba(0, 126, 255, 0.24)',
      verticalAlign: 'middle',
      padding: '1px 5px',
      fontSize: '12px',
      lineHeight: '14px',
      display: 'inline-block'
    }),
    menu: () => ({
      borderBottomRightRadius: '4px',
      borderBottomLeftRadius: '4px',
      backgroundColor: '#fff',
      border: '1px solid #ccc',
      borderTopColor: `${colors.barBorderColor}`,
      boxSizing: 'border-box',
      marginTop: '-1px',
      maxHeight: '200px',
      position: 'absolute',
      top: '100%',
      width: '100%',
      zIndex: `${zIndexes.dropdownMenuZIndex}`,
      webkitOverflowScrolling: 'touch',
      boxShadow: `${others.defaultShadow}`
    }),
    menuList: () => ({
      boxSizing: 'border-box',
      maxHeight: '198px',
      padding: '5px 0',
      overflowY: 'auto'
    }),
    placeholder: () => ({
      color: '#666'
    }),
    option: (_provided, state) => ({
      display: 'block',
      lineHeight: '20px',
      padding: '0 8px',
      boxSizing: 'border-box',
      color: `${colors.baseFontColor}`,
      backgroundColor: state.isFocused ? `${colors.barBackgroundColor}` : 'white',
      fontSize: `${sizes.smallFontSize}`,
      cursor: 'pointer',
      whiteSpace: 'nowrap',
      overflow: 'hidden',
      textOverflow: 'ellipsis'
    }),
    input: () => ({
      padding: '0px',
      margin: '0px',
      height: '100%',
      display: 'flex',
      alignItems: 'center',
      paddingLeft: '1px'
    }),
    loadingIndicator: () => ({
      position: 'absolute',
      padding: '8px',
      fontSize: '4px'
    }),
    noOptionsMessage: () => ({
      color: `${colors.gray60}`,
      padding: '8px 10px'
    })
  };
}
