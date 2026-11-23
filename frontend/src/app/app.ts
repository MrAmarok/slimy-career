import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { SideNav } from './core/side-nav';

@Component({
  imports: [RouterOutlet, SideNav],
  selector: 'app-root',
  template: '<app-side-nav /><router-outlet />',
})
export class App {}
